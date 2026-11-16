package com.school.ontology.core.model;

import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 映射版本记录（B1/B2）：定义内容哈希决定版本，落 ont_mapping_version 与
 * ont_model_def，并把当前生效版本写入 ont_data_source，供模型管理页与来源追溯查询。
 *
 * <p>加载在内存里完成（ModelDefinitionLoader 构造函数），落库延迟到
 * ApplicationRunner（@Order 20，晚于建表迁移器），避免启动期依赖表已存在。</p>
 *
 * <p><b>署名与内容留存（P6 缺陷修复）</b>：本类是「幂等补登记」而非「最终裁决」，
 * 因此 operator/note 一律由 {@link DefinitionVersionAttribution} 解析——该版本行
 * 已有人写的署名就原样保留，绝不用 {@code system / startup-overlay} 覆盖；
 * 同时把每个版本的完整定义内容按 {@code <type>@<version>} 单独留一行，
 * 使 {@code /model/versions} 能按版本号回看「那一版长什么样」。</p>
 */
@Component
@Order(20)
public class MappingVersionRecorder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MappingVersionRecorder.class);

    private final ModelDefinitionLoader loader;
    private final OntologyRepository repository;
    private final DefinitionVersionAttribution attribution;
    private volatile MappingVersion current;

    /**
     * 生产构造。
     *
     * <p>必须显式 {@code @Autowired}：本类另有一个包内可见的测试构造（多一个署名解析器参数），
     * 一旦存在多个构造，Spring 就不再走「唯一构造自动注入」，启动会直接报
     * {@code No default constructor found}。</p>
     */
    @Autowired
    public MappingVersionRecorder(ModelDefinitionLoader loader, OntologyRepository repository) {
        this(loader, repository, new DefinitionVersionAttribution(repository));
    }

    MappingVersionRecorder(ModelDefinitionLoader loader, OntologyRepository repository,
                           DefinitionVersionAttribution attribution) {
        this.loader = loader;
        this.repository = repository;
        this.current = loader.mappingVersion();
        this.attribution = attribution == null
                ? new DefinitionVersionAttribution(repository) : attribution;
    }

    public MappingVersion current() {
        return current;
    }

    public void activate(MappingVersion version) {
        this.current = version;
    }

    @Override
    public void run(ApplicationArguments args) {
        MappingVersion version = current;
        String sourceId = version.sourceId;
        String displayName = loader.sourceDocument().dataSource.displayName;
        // 定义来源由 loader 在构造期判定（BASELINE / OVERLAY / OVERLAY_FALLBACK），
        // 这里原样透传，使版本表能回答「这次生效的定义是从哪来的」。
        DefinitionVersionAttribution.Attribution credit =
                attribution.resolve(sourceId, version.mappingVersion, loader.definitionSource());
        try {
            repository.ensureDataSource(sourceId, displayName);
            repository.upsertMappingVersion(sourceId, version.mappingVersion,
                    version.definitionHash, version.status, credit.operator(), credit.note(),
                    credit.definitionSource());
            // 生效内容的「当前值」三行：保持既有读取方（模型管理页 / 来源追溯）行为不变。
            repository.upsertModelDef("object-types", "OBJECT_TYPE", loader.objectDocument(),
                    version.mappingVersion, credit.operator(), credit.note(), credit.definitionSource());
            repository.upsertModelDef("relation-types", "RELATION_TYPE", loader.relationDocument(),
                    version.mappingVersion, credit.operator(), credit.note(), credit.definitionSource());
            repository.upsertModelDef("source-mapping", "SOURCE_MAPPING", loader.sourceDocument(),
                    version.mappingVersion, credit.operator(), credit.note(), credit.definitionSource());
            // 每个版本的「历史快照」：key 带版本号，只插不改，供按版本号回看内容。
            repository.insertModelDefVersion(versionedKey("object-types", version.mappingVersion),
                    "OBJECT_TYPE", loader.objectDocument(), version.mappingVersion,
                    credit.operator(), credit.note(), credit.definitionSource());
            repository.insertModelDefVersion(versionedKey("relation-types", version.mappingVersion),
                    "RELATION_TYPE", loader.relationDocument(), version.mappingVersion,
                    credit.operator(), credit.note(), credit.definitionSource());
            repository.insertModelDefVersion(versionedKey("source-mapping", version.mappingVersion),
                    "SOURCE_MAPPING", loader.sourceDocument(), version.mappingVersion,
                    credit.operator(), credit.note(), credit.definitionSource());
            repository.updateDataSource(sourceId,
                    Map.of("mappingVersion", version.mappingVersion));
        } catch (Exception e) {
            // 本体库暂时不可达时不阻断服务启动：只读端点仍可返回已同步数据，
            // 待数据库恢复后的下一次运行/管理操作再补齐版本记录。
            log.warn("映射版本记录暂未能落库（本体库可能尚未就绪）：{}", e.getMessage());
        }
    }

    /** {@code def_key} 是主键：把版本号拼进去才能做到「一版一行、互不覆盖」。 */
    static String versionedKey(String defKey, String mappingVersion) {
        return defKey + "@" + mappingVersion;
    }
}

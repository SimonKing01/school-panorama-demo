package com.school.ontology.core.model;

import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
@Order(20)
public class MappingVersionRecorder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MappingVersionRecorder.class);

    private final ModelDefinitionLoader loader;
    private final OntologyRepository repository;
    private volatile MappingVersion current;

    public MappingVersionRecorder(ModelDefinitionLoader loader, OntologyRepository repository) {
        this.loader = loader;
        this.repository = repository;
        this.current = loader.mappingVersion();
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
        try {
            repository.ensureDataSource(sourceId, displayName);
            repository.upsertMappingVersion(sourceId, version.mappingVersion,
                    version.definitionHash, version.status);
            repository.upsertModelDef("object-types", "OBJECT_TYPE",
                    loader.objectDocument(), version.mappingVersion);
            repository.upsertModelDef("relation-types", "RELATION_TYPE",
                    loader.relationDocument(), version.mappingVersion);
            repository.upsertModelDef("source-mapping", "SOURCE_MAPPING",
                    loader.sourceDocument(), version.mappingVersion);
            repository.updateDataSource(sourceId,
                    Map.of("mappingVersion", version.mappingVersion));
        } catch (Exception e) {
            // 本体库暂时不可达时不阻断服务启动：只读端点仍可返回已同步数据，
            // 待数据库恢复后的下一次运行/管理操作再补齐版本记录。
            log.warn("映射版本记录暂未能落库（本体库可能尚未就绪）：{}", e.getMessage());
        }
    }
}

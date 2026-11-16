package com.school.ontology.core.model;

import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 版本署名解析（P6 缺陷修复）：决定「这一版生效时，operator/note 该记在谁头上」。
 *
 * <p><b>要解决的缺陷</b>：保存草稿时库里已经写了人写的 operator/note，但服务重启激活时
 * {@link MappingVersionRecorder} / {@link DefinitionActivationRunner} 会用
 * {@code system / startup-overlay} 无条件 upsert，把人名覆盖掉，导致
 * {@code ont_mapping_version} 里「谁改的」永久丢失。</p>
 *
 * <p><b>规则（唯一一份，两处调用方共用，避免两边判断漂移）</b>：</p>
 * <ol>
 *   <li>该版本行已有人写的署名 → <b>原样保留</b> operator 与 note（文档说的「原地转 ACTIVE」）；</li>
 *   <li>该版本行没有署名（新版本 / 手工改了 yaml 文件 / 纯基线首启）→
 *       记 {@code system} + {@code startup-<definition-source>}。</li>
 * </ol>
 *
 * <p>纯基线首启没有历史行，走第 2 条自然得到 {@code system / startup-baseline}，
 * 与设计文档口径一致。</p>
 */
@Component
public class DefinitionVersionAttribution {

    /** 登记器自己的占位操作人：不算「人写的署名」，可被后续人工署名覆盖。 */
    public static final String SYSTEM_OPERATOR = "system";
    private static final Logger log = LoggerFactory.getLogger(DefinitionVersionAttribution.class);

    private final OntologyRepository repository;

    public DefinitionVersionAttribution(OntologyRepository repository) {
        this.repository = repository;
    }

    /**
     * 解析署名。
     *
     * @param sourceId      来源 id
     * @param mappingVersion 本次要激活 / 登记的版本号
     * @param detectedSource 本次启动 loader 判定的定义来源（BASELINE / OVERLAY / OVERLAY_FALLBACK）
     * @return 落库用的署名三元组（永不为 null 字段）
     */
    public Attribution resolve(String sourceId, String mappingVersion, String detectedSource) {
        String defaultNote = startupNote(detectedSource);
        Map<String, Object> row = safeRow(sourceId, mappingVersion);
        String operator = OntologyRepository.str(row.get("operator"));
        if (operator == null || operator.isBlank() || SYSTEM_OPERATOR.equals(operator)) {
            // 没有人工署名：登记器自己记一笔，note 说明是哪种定义来源。
            return new Attribution(SYSTEM_OPERATOR, defaultNote, detectedSource);
        }
        String note = OntologyRepository.str(row.get("note"));
        String rowSource = OntologyRepository.str(row.get("definition_source"));
        // 回退（坏 overlay 被隔离）是启动期才可能发现的事实，必须如实覆盖旧来源；
        // 其余情况沿用当初保存时的来源，避免「保存时是 OVERLAY，激活后莫名变成 BASELINE」。
        String definitionSource = "OVERLAY_FALLBACK".equals(detectedSource)
                ? detectedSource
                : (rowSource == null || rowSource.isBlank() ? detectedSource : rowSource);
        return new Attribution(operator, note == null || note.isBlank() ? defaultNote : note,
                definitionSource);
    }

    /** {@code startup-baseline} / {@code startup-overlay} / {@code startup-overlay-fallback}。 */
    public static String startupNote(String definitionSource) {
        String source = definitionSource == null || definitionSource.isBlank()
                ? "BASELINE" : definitionSource;
        return "startup-" + source.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** 该署名是否来自真人（而非登记器占位）。 */
    public static boolean isHuman(String operator) {
        return operator != null && !operator.isBlank() && !SYSTEM_OPERATOR.equals(operator);
    }

    private Map<String, Object> safeRow(String sourceId, String mappingVersion) {
        try {
            Map<String, Object> row = repository.mappingVersionRow(sourceId, mappingVersion);
            return row == null ? Map.of() : row;
        } catch (Exception e) {
            // 库暂时不可达：按「无历史署名」处理，不阻断启动（下一轮重启会重新解析）。
            log.warn("读取版本行署名失败，按无署名处理：{}", e.getMessage());
            return Map.of();
        }
    }

    /** 落库三元组。 */
    public record Attribution(String operator, String note, String definitionSource) {

        public boolean human() {
            return isHuman(operator);
        }
    }
}

package com.school.ontology.core.model;

import com.school.ontology.common.Resp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 关系类型在线编辑的校验器（07 增量设计 §7）。
 *
 * <p>刻意「只补 loader 没覆盖的规则」，集成级校验**直接复用**
 * {@link ModelDefinitionLoader#validate}：这样「保存通过 == 启动通过」，
 * 不会出现两套规则漂移导致的「页面能存、重启起不来」。</p>
 *
 * <p>报错顺序即用户看到顺序：字段级 → 集成级；全部中文。</p>
 */
@Component
public class RelationTypeValidator {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> DIRECTIONS = Set.of("OUT", "IN");
    private static final Set<String> CARDINALITIES = Set.of("N:1", "1:1", "1:N", "N:M");
    private static final Set<String> GENERATION_KINDS = Set.of("FOREIGN_KEY", "JOIN_RULE");
    private static final String SOURCE_MAPPING_PREFIX = "school-source.yaml#";

    private final ModelDefinitionLoader loader;
    private final ModelRegistry registry;

    public RelationTypeValidator(ModelDefinitionLoader loader, ModelRegistry registry) {
        this.loader = loader;
        this.registry = registry;
    }

    /**
     * 字段级规则（§7 第 1–6、8 组）。
     *
     * @param def    待保存的定义
     * @param create true=新增（id 不能已存在），false=修改（id 必须等于路径 id 且必须存在）
     * @param pathId PUT 路径上的 id；新增时传 null
     */
    public List<String> validateFields(RelationTypeDef def, boolean create, String pathId) {
        Set<String> ids = new LinkedHashSet<>();
        for (RelationTypeDef existing : registry.relations()) {
            if (existing != null && !isBlank(existing.id)) {
                ids.add(existing.id);
            }
        }
        return validateFields(def, create, pathId, ids);
    }

    /**
     * 字段级规则重载：{@code existingIds} 传「当前生效草稿」里的 id 集合。
     *
     * <p>为什么需要它：进程内 {@code ModelRegistry} 只在启动时构造，保存过的草稿不进去
     * （本方案靠重启生效）。若唯一性/存在性判断只看 registry，就会出现「刚保存的新关系
     * 在同一进程里改不了、也删不掉」。因此由调用方把草稿文档的 id 集合传进来。</p>
     */
    public List<String> validateFields(RelationTypeDef def, boolean create, String pathId,
                                       Set<String> existingIds) {
        List<String> errors = new ArrayList<>();
        if (def == null) {
            errors.add("关系类型定义不能为空");
            return errors;
        }
        Set<String> ids = existingIds == null ? Set.of() : existingIds;
        // 1) id 格式；2) 唯一性 / 修改时等于路径 id
        if (isBlank(def.id) || !IDENTIFIER.matcher(def.id).matches()) {
            errors.add("关系类型 id 非法：必须以字母开头，仅含字母、数字、下划线");
        } else if (create) {
            if (ids.contains(def.id)) {
                errors.add("关系类型 id 已存在：" + def.id);
            }
        } else {
            if (pathId != null && !pathId.equals(def.id)) {
                errors.add("不允许通过修改端点改 id（请删除后新增）：路径 id=" + pathId + "，请求 id=" + def.id);
            }
            if (!ids.contains(pathId == null ? def.id : pathId)) {
                errors.add("关系类型不存在：" + (pathId == null ? def.id : pathId));
            }
        }
        // 3) 起止类型存在性
        checkObjectType(errors, def.fromType, "起点");
        checkObjectType(errors, def.toType, "终点");
        // 4) 展示名
        if (isBlank(def.displayName)) {
            errors.add("displayName 不能为空");
        }
        if (isBlank(def.forwardName)) {
            errors.add("正向展示名不能为空");
        }
        if (isBlank(def.reverseName)) {
            errors.add("反向展示名不能为空");
        }
        // 5) 方向与基数
        if (isBlank(def.direction)) {
            errors.add("direction 不能为空：必须是 OUT 或 IN");
        } else if (!DIRECTIONS.contains(def.direction)) {
            errors.add("方向非法：" + def.direction + "（必须是 OUT 或 IN）");
        }
        if (isBlank(def.cardinality)) {
            errors.add("cardinality 不能为空：必须是 N:1、1:1、1:N、N:M 之一");
        } else if (!CARDINALITIES.contains(def.cardinality)) {
            errors.add("基数非法：" + def.cardinality + "（必须是 N:1、1:1、1:N、N:M 之一）");
        }
        // 6) 生成规则分支必填
        errors.addAll(validateGeneration(def.generation));
        // 8) sourceMappingRef 必须指向已存在的对象映射（loader.validate 未覆盖）
        errors.addAll(validateSourceMappingRef(def.sourceMappingRef));
        return errors;
    }

    /**
     * 集成级规则（§7 第 7 组）：用「基线对象文档 + 候选关系文档 + 基线来源文档」
     * 走一遍启动同一套 {@code validate}，保证「保存通过 = 启动通过」。
     */
    public List<String> validateIntegration(ModelDocument.RelationTypeDocument candidate) {
        return loader.validate(loader.objectDocument(), candidate, loader.sourceDocument());
    }

    /**
     * 删除前的引用影响分析（不阻止删除，只产出清单；是否放行由调用方按 force 决定）。
     */
    public DeleteImpact analyzeDelete(String relationId) {
        return analyzeDelete(relationId,
                registry.hasRelation(relationId) ? registry.relationOf(relationId) : null);
    }

    /**
     * 删除影响分析重载：{@code def} 取「当前草稿文档」里的定义（草稿里的新关系不在 registry 中）。
     */
    public DeleteImpact analyzeDelete(String relationId, RelationTypeDef def) {
        List<Map<String, Object>> referencedByMappings = new ArrayList<>();
        Set<String> impactedObjectTypes = new LinkedHashSet<>();
        ModelDocument.SourceMappingDocument source = loader.sourceDocument();
        for (Map.Entry<String, ObjectMappingDef> entry : source.objectMappings.entrySet()) {
            String objectType = entry.getKey();
            ObjectMappingDef mapping = entry.getValue();
            if (mapping == null) {
                continue;
            }
            if (mapping.relationRules != null) {
                for (int i = 0; i < mapping.relationRules.size(); i++) {
                    ObjectMappingDef.RelationRuleDef rule = mapping.relationRules.get(i);
                    if (rule != null && relationId.equals(rule.relationType)) {
                        referencedByMappings.add(Resp.map(
                                "objectType", objectType,
                                "rule", "relationRules[" + i + "].relationType=" + relationId));
                        impactedObjectTypes.add(objectType);
                        if (!isBlank(rule.targetObjectType)) {
                            impactedObjectTypes.add(rule.targetObjectType);
                        }
                    }
                }
            }
            if (mapping.joinRelationRules != null) {
                for (int i = 0; i < mapping.joinRelationRules.size(); i++) {
                    ObjectMappingDef.JoinRelationRule rule = mapping.joinRelationRules.get(i);
                    if (rule != null && relationId.equals(rule.relationType)) {
                        referencedByMappings.add(Resp.map(
                                "objectType", objectType,
                                "rule", "joinRelationRules[" + i + "].relationType=" + relationId));
                        impactedObjectTypes.add(objectType);
                        if (!isBlank(rule.targetObjectType)) {
                            impactedObjectTypes.add(rule.targetObjectType);
                        }
                    }
                }
            }
        }
        if (def != null) {
            if (!isBlank(def.fromType)) {
                impactedObjectTypes.add(def.fromType);
            }
            if (!isBlank(def.toType)) {
                impactedObjectTypes.add(def.toType);
            }
        }
        String hint = referencedByMappings.isEmpty()
                ? "该关系没有被任何映射规则引用，删除后不会影响既有的关系生成"
                : "该关系仍被 " + referencedByMappings.size()
                        + " 处映射引用，删除后这些映射不再生成该关系；确认请带 force=true 重发";
        return new DeleteImpact(relationId, referencedByMappings,
                new ArrayList<>(impactedObjectTypes), hint);
    }

    /** 删除影响清单。 */
    public record DeleteImpact(String relationId, List<Map<String, Object>> referencedByMappings,
                               List<String> impactedObjectTypes, String hint) {
    }

    /** §7 第 8 组：{@code sourceMappingRef = school-source.yaml#<objectType>} 且该映射必须存在。 */
    private List<String> validateSourceMappingRef(String sourceMappingRef) {
        if (isBlank(sourceMappingRef)) {
            return List.of();
        }
        if (!sourceMappingRef.startsWith(SOURCE_MAPPING_PREFIX)) {
            return List.of("sourceMappingRef 必须以 " + SOURCE_MAPPING_PREFIX + "<objectType> 形式给出："
                    + sourceMappingRef);
        }
        String objectType = sourceMappingRef.substring(SOURCE_MAPPING_PREFIX.length());
        if (isBlank(objectType) || !hasMapping(objectType)) {
            return List.of("sourceMappingRef 指向不存在的映射：" + sourceMappingRef
                    + "（可选：" + String.join("、", mappingTypes()) + "）");
        }
        return List.of();
    }

    private List<String> validateGeneration(GenerationRule generation) {
        List<String> errors = new ArrayList<>();
        if (generation == null || isBlank(generation.kind)) {
            errors.add("generation.kind 不能为空：必须是 FOREIGN_KEY 或 JOIN_RULE");
            return errors;
        }
        if (!GENERATION_KINDS.contains(generation.kind)) {
            errors.add("generation.kind 非法：" + generation.kind + "（必须是 FOREIGN_KEY 或 JOIN_RULE）");
            return errors;
        }
        if ("FOREIGN_KEY".equals(generation.kind)) {
            if (isBlank(generation.sourceTable)) {
                errors.add("FOREIGN_KEY 缺少 sourceTable");
            }
            if (isBlank(generation.fkColumn)) {
                errors.add("FOREIGN_KEY 缺少 fkColumn");
            }
            if (isBlank(generation.targetTable)) {
                errors.add("FOREIGN_KEY 缺少 targetTable");
            }
        } else {
            if (isBlank(generation.sourceColumn)) {
                errors.add("JOIN_RULE 缺少 sourceColumn");
            }
            if (isBlank(generation.joinTable)) {
                errors.add("JOIN_RULE 缺少 joinTable");
            }
            if (isBlank(generation.joinFilterColumn)) {
                errors.add("JOIN_RULE 缺少 joinFilterColumn");
            }
            if (isBlank(generation.joinSelectColumn)) {
                errors.add("JOIN_RULE 缺少 joinSelectColumn");
            }
            if (isBlank(generation.targetTable)) {
                errors.add("JOIN_RULE 缺少 targetTable");
            }
        }
        return errors;
    }

    private void checkObjectType(List<String> errors, String typeId, String role) {
        if (isBlank(typeId)) {
            errors.add(role + "类型不能为空");
            return;
        }
        if (!registry.hasType(typeId)) {
            errors.add(role + "类型不存在：" + typeId + "（可选："
                    + String.join("、", registry.orderedTypeIds()) + "）");
        }
    }

    /** {@code ModelRegistry} 没有 hasMapping，按 objectType 遍历已注册映射判断。 */
    private boolean hasMapping(String objectType) {
        for (ObjectMappingDef mapping : registry.mappings()) {
            if (mapping != null && objectType.equals(mapping.objectType)) {
                return true;
            }
        }
        return false;
    }

    private List<String> mappingTypes() {
        List<String> types = new ArrayList<>();
        for (ObjectMappingDef mapping : registry.mappings()) {
            if (mapping != null && !isBlank(mapping.objectType)) {
                types.add(mapping.objectType);
            }
        }
        return types;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package com.school.ontology.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关系类型校验规则（07 增量设计 §7 第 1–8 组）逐条正/负例。 */
class RelationTypeValidatorTest {

    private final ModelDefinitionLoader loader = new ModelDefinitionLoader();
    private final RelationTypeValidator validator =
            new RelationTypeValidator(loader, loader.registry());

    @Test
    void 合法定义不产生任何错误() {
        List<String> errors = validator.validateFields(validDefinition("new_relation"), true, null);
        assertTrue(errors.isEmpty(), "合法定义不应报错，实际：" + errors);
        assertTrue(validator.validateIntegration(documentOf(validDefinition("new_relation"))).isEmpty());
    }

    @Test
    void id格式非法时报中文错误() {
        List<String> errors = validator.validateFields(validDefinition("1-bad id"), true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("关系类型 id 非法")), "实际：" + errors);
    }

    @Test
    void 新增时id重复被拒绝() {
        List<String> errors = validator.validateFields(validDefinition("session_taught_by"), true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("关系类型 id 已存在")), "实际：" + errors);
    }

    @Test
    void 修改时不允许改id() {
        RelationTypeDef def = validDefinition("another_id");
        List<String> errors = validator.validateFields(def, false, "session_taught_by");
        assertTrue(errors.stream().anyMatch(e -> e.contains("不允许通过修改端点改 id")), "实际：" + errors);
    }

    @Test
    void 修改不存在的id被拒绝() {
        List<String> errors = validator.validateFields(validDefinition("not_exists"), false, "not_exists");
        assertTrue(errors.stream().anyMatch(e -> e.contains("关系类型不存在")), "实际：" + errors);
    }

    @Test
    void 起止对象类型不存在时报出可选清单() {
        RelationTypeDef def = validDefinition("new_relation");
        def.fromType = "classroom2";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("起点类型不存在：classroom2")
                && e.contains("classroom")), "错误应带上可选项，实际：" + errors);
    }

    @Test
    void 三个展示名缺一不可() {
        RelationTypeDef def = validDefinition("new_relation");
        def.displayName = " ";
        def.forwardName = null;
        def.reverseName = "";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("displayName 不能为空")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("正向展示名不能为空")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("反向展示名不能为空")), "实际：" + errors);
    }

    @Test
    void 方向与基数只接受枚举值() {
        RelationTypeDef def = validDefinition("new_relation");
        def.direction = "BOTH";
        def.cardinality = "1:2";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("方向非法：BOTH")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("基数非法：1:2")), "实际：" + errors);
    }

    @Test
    void 外键分支缺少必填项时报错() {
        RelationTypeDef def = validDefinition("new_relation");
        def.generation.fkColumn = null;
        def.generation.sourceTable = "";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("FOREIGN_KEY 缺少 sourceTable")), "实际：" + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("FOREIGN_KEY 缺少 fkColumn")), "实际：" + errors);
    }

    @Test
    void 联表分支缺少必填项时报错() {
        RelationTypeDef def = validDefinition("new_relation");
        def.generation.kind = "JOIN_RULE";
        def.generation.sourceColumn = "class_id";
        def.generation.joinTable = "lesson_sessions";
        def.generation.joinFilterColumn = "class_id";
        def.generation.joinSelectColumn = null;
        def.generation.targetTable = "teachers";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("JOIN_RULE 缺少 joinSelectColumn")), "实际：" + errors);
    }

    @Test
    void 生成规则kind非法时直接拒绝() {
        RelationTypeDef def = validDefinition("new_relation");
        def.generation.kind = "MAGIC";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("generation.kind 非法：MAGIC")), "实际：" + errors);
    }

    @Test
    void sourceMappingRef必须指向已存在的映射() {
        RelationTypeDef def = validDefinition("new_relation");
        def.sourceMappingRef = "school-source.yaml#notExist";
        List<String> errors = validator.validateFields(def, true, null);
        assertTrue(errors.stream().anyMatch(e -> e.contains("sourceMappingRef 指向不存在的映射")
                && e.contains("school-source.yaml#notExist")), "实际：" + errors);

        RelationTypeDef badPrefix = validDefinition("new_relation");
        badPrefix.sourceMappingRef = "other.yaml#student";
        List<String> prefixErrors = validator.validateFields(badPrefix, true, null);
        assertTrue(prefixErrors.stream().anyMatch(e -> e.contains("sourceMappingRef 必须以")),
                "实际：" + prefixErrors);
    }

    @Test
    void 集成级校验能发现整份文档的不一致() {
        ModelDocument.RelationTypeDocument document = new ModelDocument.RelationTypeDocument();
        document.version = 1;
        // 丢掉一个仍被 school-source.yaml 引用的关系类型 → 集成校验必挂。
        for (RelationTypeDef def : loader.relationDocument().relationTypes) {
            if (!"student_taught_by".equals(def.id)) {
                document.relationTypes.add(loader.registry().relationOf(def.id));
            }
        }
        List<String> errors = validator.validateIntegration(document);
        assertTrue(errors.stream().anyMatch(e -> e.contains("引用了未定义的关系类型：student_taught_by")),
                "集成校验必须复用启动同一套规则，实际：" + errors);
    }

    @Test
    void 删除影响分析列出映射引用与受影响对象类型() {
        RelationTypeValidator.DeleteImpact impact = validator.analyzeDelete("student_taught_by");

        assertEquals(1, impact.referencedByMappings().size(),
                "student_taught_by 被 student.joinRelationRules[0] 引用一次，实际：" + impact);
        assertEquals("student", impact.referencedByMappings().get(0).get("objectType"));
        assertTrue(String.valueOf(impact.referencedByMappings().get(0).get("rule"))
                .contains("student_taught_by"));
        assertTrue(impact.impactedObjectTypes().containsAll(List.of("student", "teacher")),
                "受影响对象类型应含两端，实际：" + impact.impactedObjectTypes());
        assertTrue(impact.hint().contains("force=true"), "影响提示要给出确认方式，实际：" + impact.hint());
    }

    @Test
    void 未被引用的关系删除影响为空() {
        RelationTypeValidator.DeleteImpact impact =
                validator.analyzeDelete("draft_only_relation", validDefinition("draft_only_relation"));
        assertTrue(impact.referencedByMappings().isEmpty());
        assertTrue(impact.hint().contains("不会影响"), "实际：" + impact.hint());
        assertFalse(impact.impactedObjectTypes().isEmpty(), "两端对象类型仍应列出");
    }

    /** 一条字段级完全合法的外键关系定义（学号 → 教师，仅为校验使用）。 */
    private static RelationTypeDef validDefinition(String id) {
        RelationTypeDef def = new RelationTypeDef();
        def.id = id;
        def.displayName = "学生-教师（测试）";
        def.fromType = "student";
        def.toType = "teacher";
        def.forwardName = "教师";
        def.reverseName = "学生";
        def.direction = "OUT";
        def.cardinality = "N:1";
        def.businessEffectiveTime = false;
        def.sourceMappingRef = "school-source.yaml#student";
        GenerationRule generation = new GenerationRule();
        generation.kind = "FOREIGN_KEY";
        generation.sourceTable = "students";
        generation.fkColumn = "teacher_id";
        generation.targetTable = "teachers";
        def.generation = generation;
        return def;
    }

    private static ModelDocument.RelationTypeDocument documentOf(RelationTypeDef extra) {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        ModelDocument.RelationTypeDocument document = new ModelDocument.RelationTypeDocument();
        document.version = 1;
        for (RelationTypeDef def : baseline.relationDocument().relationTypes) {
            document.relationTypes.add(def);
        }
        document.relationTypes.add(extra);
        return document;
    }
}

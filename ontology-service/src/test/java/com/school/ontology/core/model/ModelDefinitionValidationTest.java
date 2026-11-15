package com.school.ontology.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1「非法定义加载报错」与「7 类对象 + 授课安排由定义产出」的正/负例验证。 */
class ModelDefinitionValidationTest {

    @Test
    void 合法定义加载成功且产出七类对象与授课安排() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelRegistry registry = loader.registry();

        assertEquals(7, registry.orderedTypeIds().size(),
                "学校/班级/学生/教师/课程 + 授课安排 + 教室（批次④ 接入）共 7 类");
        assertTrue(registry.orderedTypeIds().contains("lessonSession"));
        assertTrue(registry.hasRelation("session_taught_by"));
        assertTrue(registry.hasType("classroom"), "教室在批次④ 由 YAML 定义接入");
        assertTrue(registry.hasRelation("session_in_classroom"));
        assertNotNull(loader.mappingVersion().mappingVersion);
        assertTrue(loader.mappingVersion().mappingVersion.startsWith("school-panorama:"));
        assertTrue(loader.validate(loader.objectDocument(), loader.relationDocument(),
                loader.sourceDocument()).isEmpty());
    }

    /**
     * 批次④ 验收：教室类型由「纯 YAML 定义 + 一个适配层 Provider」接入，
     * 此处逐项断言对象/属性/字段映射/关系/派生属性/SPI 注册齐全，证明内核无需为新类型写代码。
     */
    @Test
    void 教室类型的关系映射与派生属性齐全() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelRegistry registry = loader.registry();

        ObjectTypeDef classroom = registry.typeOf("classroom");
        assertEquals("classrooms", classroom.identity.sourceTable, "教室来源表必须是 classrooms");
        for (String name : List.of("code", "name", "capacity", "building")) {
            assertNotNull(classroom.attribute(name), "classroom 缺少属性 " + name);
        }
        assertEquals("string", classroom.attribute("code").type);
        assertEquals("int", classroom.attribute("capacity").type);

        ObjectMappingDef mapping = registry.mappingOf("classroom");
        assertEquals("classrooms", mapping.sourceTable);
        assertEquals("id", mapping.pk);
        assertTrue(mapping.fieldMappings.stream().anyMatch(fm -> "schoolName".equals(fm.target)
                        && "LOOKUP_NAME".equals(fm.transform)),
                "所属学校必须走 LOOKUP_NAME 策略表，内核不写联表 SQL");
        assertTrue(mapping.relationRules.stream().anyMatch(rule ->
                        "classroom_in_school".equals(rule.relationType)
                                && "school_id".equals(rule.fk)
                                && "school".equals(rule.targetObjectType)),
                "教室必须声明 classroom_in_school 外键关系");
        assertTrue(mapping.derivedProperties.stream()
                        .allMatch(binding -> "classroomUsageProvider".equals(binding.provider)),
                "教室派生属性必须全部由 classroomUsageProvider 计算");

        RelationTypeDef sessionInRoom = registry.relationOf("session_in_classroom");
        assertEquals("lessonSession", sessionInRoom.fromType);
        assertEquals("classroom", sessionInRoom.toType);
        assertEquals("classroom_id", sessionInRoom.generation.fkColumn);
        assertEquals(List.of("lesson_date", "start_time"), sessionInRoom.generation.validFromColumns,
                "授课安排的教室关系同样按定义列推导业务有效时间");

        ObjectMappingDef lessonSession = registry.mappingOf("lessonSession");
        assertTrue(lessonSession.relationRules.stream().anyMatch(rule ->
                        "session_in_classroom".equals(rule.relationType) && "classroom_id".equals(rule.fk)),
                "授课安排必须声明教室关系规则，教室侧才能反查使用安排");

        assertTrue(loader.sourceDocument().providers.stream().anyMatch(binding ->
                        "classroomUsageProvider".equals(binding.key)
                                && "com.school.ontology.adapter.school.provider.ClassroomUsageProvider"
                                .equals(binding.impl)),
                "providers 清单必须注册 ClassroomUsageProvider");
    }

    @Test
    void 关系定义引用未注册类型时应报校验错误() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelDocument.RelationTypeDocument broken = new ModelDocument.RelationTypeDocument();
        broken.version = 1;
        broken.relationTypes = new ArrayList<>();
        for (RelationTypeDef def : loader.relationDocument().relationTypes) {
            RelationTypeDef copy = shallowCopy(def);
            if ("student_in_class".equals(copy.id)) {
                copy.toType = "unknown_type";
            }
            broken.relationTypes.add(copy);
        }
        List<String> errors = loader.validate(loader.objectDocument(), broken,
                loader.sourceDocument());
        assertTrue(errors.stream().anyMatch(e -> e.contains("起止类型")),
                "起止类型必须已在对象类型定义中注册，实际错误：" + errors);
    }

    @Test
    void 全局身份规则被写错时应报校验错误() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelDocument.ObjectTypeDocument broken = new ModelDocument.ObjectTypeDocument();
        broken.version = 1;
        broken.objectTypes = new ArrayList<>();
        for (ObjectTypeDef def : loader.objectDocument().objectTypes) {
            ObjectTypeDef copy = new ObjectTypeDef();
            copy.id = def.id;
            copy.displayName = def.displayName;
            copy.description = def.description;
            copy.titleTemplate = def.titleTemplate;
            copy.listFields = def.listFields;
            copy.searchableFields = def.searchableFields;
            copy.filterableFields = def.filterableFields;
            copy.attributes = def.attributes;
            copy.derivedProperties = def.derivedProperties;
            copy.dynamicResolvers = def.dynamicResolvers;
            copy.sourceMappingRef = def.sourceMappingRef;
            ObjectTypeDef.IdentityRule identity = new ObjectTypeDef.IdentityRule();
            identity.sourceId = def.identity.sourceId;
            identity.sourceTable = def.identity.sourceTable;
            identity.sourcePk = def.identity.sourcePk;
            identity.globalIdRule = "byName:{name}";
            copy.identity = identity;
            broken.objectTypes.add(copy);
        }
        List<String> errors = loader.validate(broken, loader.relationDocument(),
                loader.sourceDocument());
        assertTrue(errors.stream().anyMatch(e -> e.contains("globalIdRule")),
                "非法身份规则必须被拒绝，实际错误：" + errors);
    }

    private static RelationTypeDef shallowCopy(RelationTypeDef def) {
        RelationTypeDef copy = new RelationTypeDef();
        copy.id = def.id;
        copy.displayName = def.displayName;
        copy.fromType = def.fromType;
        copy.toType = def.toType;
        copy.forwardName = def.forwardName;
        copy.reverseName = def.reverseName;
        copy.direction = def.direction;
        copy.cardinality = def.cardinality;
        copy.businessEffectiveTime = def.businessEffectiveTime;
        copy.generation = def.generation;
        copy.sourceMappingRef = def.sourceMappingRef;
        return copy;
    }
}

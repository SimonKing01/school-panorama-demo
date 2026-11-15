package com.school.ontology.core.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B1「非法定义加载报错」与「6 类对象 + 授课安排由定义产出」的正/负例验证。 */
class ModelDefinitionValidationTest {

    @Test
    void 合法定义加载成功且产出六类对象与授课安排() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelRegistry registry = loader.registry();

        assertEquals(6, registry.orderedTypeIds().size(), "学校/班级/学生/教师/课程 + 授课安排共 6 类");
        assertTrue(registry.orderedTypeIds().contains("lessonSession"));
        assertTrue(registry.hasRelation("session_taught_by"));
        assertFalse(registry.hasType("classroom"), "教室在批次①②③ 应保持注释预留状态");
        assertFalse(registry.hasRelation("session_in_classroom"));
        assertNotNull(loader.mappingVersion().mappingVersion);
        assertTrue(loader.mappingVersion().mappingVersion.startsWith("school-panorama:"));
        assertTrue(loader.validate(loader.objectDocument(), loader.relationDocument(),
                loader.sourceDocument()).isEmpty());
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

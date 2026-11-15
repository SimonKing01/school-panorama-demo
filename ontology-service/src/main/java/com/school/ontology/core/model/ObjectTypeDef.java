package com.school.ontology.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对象类型定义（B1）：一套定义同时驱动实例生成、列表列、详情字段、可搜索项与筛选器。
 * 字段为 public 是为了让 Jackson YAML 直接绑定，减少样板代码；加载后会做强校验。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ObjectTypeDef {

    public String id;
    public String displayName;
    public String description;
    public IdentityRule identity;
    public String titleTemplate;
    public List<String> listFields = new ArrayList<>();
    public List<String> searchableFields = new ArrayList<>();
    public List<String> filterableFields = new ArrayList<>();
    public List<AttributeDef> attributes = new ArrayList<>();
    public List<DerivedPropertyDef> derivedProperties = new ArrayList<>();
    public List<String> dynamicResolvers = new ArrayList<>();
    public String sourceMappingRef;

    /** 稳定身份规则：全局 ID 只由来源标识、对象类型、来源主键构成，不含名称。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IdentityRule {
        public String sourceId;
        public String sourceTable;
        public String sourcePk;
        public String globalIdRule;
    }

    /** 属性名 + 派生属性名，用于校验 listFields/searchableFields 是否合法。 */
    public Set<String> knownFieldNames() {
        Set<String> names = new LinkedHashSet<>();
        for (AttributeDef def : attributes) {
            if (def != null && def.name != null) {
                names.add(def.name);
            }
        }
        for (DerivedPropertyDef def : derivedProperties) {
            if (def != null && def.name != null) {
                names.add(def.name);
            }
        }
        return names;
    }

    public AttributeDef attribute(String name) {
        for (AttributeDef def : attributes) {
            if (def != null && name.equals(def.name)) {
                return def;
            }
        }
        return null;
    }
}

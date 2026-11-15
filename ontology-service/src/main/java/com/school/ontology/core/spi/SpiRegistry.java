package com.school.ontology.core.spi;

import com.school.ontology.core.model.FieldMappingDef;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelDocument;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.model.RelationTypeDef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 注册中心：Spring 收集全部扩展点实现，按 key 放入策略表。
 *
 * <p>内核所有分派都是 {@code map.get(key)}，绝不出现
 * {@code if (type.equals("student"))} / {@code switch(type)}。新增对象类型只需
 * 改 YAML 定义与 adapter 实现，本类与通用内核零改动。</p>
 *
 * <p>构造期同时做「定义引用的 key 必须已注册」的强校验，缺实现即启动失败，
 * 避免运行期空指针导致「只建元数据表不用」的空壳。</p>
 */
@Component
public class SpiRegistry {

    private final Map<String, FieldMapping> fieldMappings = new HashMap<>();
    private final Map<String, RelationRule> relationRules = new HashMap<>();
    private final Map<String, DerivedPropertyProvider> providers = new HashMap<>();
    private final Map<String, DynamicResolver> resolvers = new HashMap<>();
    private final List<Map<String, String>> providerBindings = new ArrayList<>();
    private final List<Map<String, String>> resolverBindings = new ArrayList<>();

    public SpiRegistry(ModelDefinitionLoader loader,
                       List<FieldMapping> fieldMappingBeans,
                       List<RelationRule> relationRuleBeans,
                       List<DerivedPropertyProvider> providerBeans,
                       List<DynamicResolver> resolverBeans) {
        for (FieldMapping bean : fieldMappingBeans) {
            putUnique(fieldMappings, bean.key(), bean, "字段转换");
        }
        for (DerivedPropertyProvider bean : providerBeans) {
            putUnique(providers, bean.key(), bean, "派生属性 Provider");
        }
        for (DynamicResolver bean : resolverBeans) {
            putUnique(resolvers, bean.key(), bean, "动态 Resolver");
            for (String alias : bean.aliases()) {
                putUnique(resolvers, alias, bean, "动态 Resolver 别名");
            }
        }
        // 关系规则实现按「生成规则 kind」注册，再按模型定义把关系类型 id 映射到实现。
        Map<String, RelationRule> byKind = new HashMap<>();
        for (RelationRule bean : relationRuleBeans) {
            byKind.put(bean.relationType(), bean);
        }
        ModelRegistry registry = loader.registry();
        for (RelationTypeDef def : registry.relations()) {
            RelationRule rule = byKind.get(def.generation.kind);
            if (rule == null) {
                throw new IllegalStateException("关系生成规则 " + def.generation.kind + " 没有对应实现");
            }
            relationRules.put(def.id, rule);
        }

        ModelDocument.SourceMappingDocument source = loader.sourceDocument();
        for (ModelDocument.SpiBinding binding : source.providers) {
            providerBindings.add(Map.of("key", binding.key, "impl", binding.impl));
        }
        for (ModelDocument.SpiBinding binding : source.resolvers) {
            resolverBindings.add(Map.of("key", binding.key, "impl", binding.impl));
        }
        validateDefinitions(loader);
    }

    private void validateDefinitions(ModelDefinitionLoader loader) {
        List<String> errors = new ArrayList<>();
        ModelDocument.SourceMappingDocument source = loader.sourceDocument();
        for (ObjectMappingDef mapping : source.objectMappings.values()) {
            for (FieldMappingDef fm : mapping.fieldMappings) {
                if (fm.transform != null && !fm.transform.isBlank() && !fieldMappings.containsKey(fm.transform)) {
                    errors.add("字段转换未注册：" + fm.transform);
                }
            }
            for (ObjectMappingDef.DerivedPropertyBinding binding : mapping.derivedProperties) {
                if (binding.provider != null && !binding.provider.isBlank()
                        && !providers.containsKey(binding.provider)) {
                    errors.add("Provider 未注册：" + binding.provider);
                }
                if (binding.resolver != null && !binding.resolver.isBlank()
                        && !resolvers.containsKey(binding.resolver)) {
                    errors.add("Resolver 未注册：" + binding.resolver);
                }
            }
        }
        for (ObjectTypeDef def : loader.objectDocument().objectTypes) {
            for (String key : def.dynamicResolvers) {
                if (!resolvers.containsKey(key)) {
                    errors.add("动态 Resolver 未注册：" + key);
                }
            }
            for (var derived : def.derivedProperties) {
                if (derived.resolver != null && !derived.resolver.isBlank()
                        && !resolvers.containsKey(derived.resolver)) {
                    errors.add("派生属性引用的 Resolver 未注册：" + derived.resolver);
                }
                if (derived.provider != null && !derived.provider.isBlank()
                        && !providers.containsKey(derived.provider)) {
                    errors.add("派生属性引用的 Provider 未注册：" + derived.provider);
                }
            }
        }
        for (ModelDocument.SpiBinding binding : source.providers) {
            if (!providers.containsKey(binding.key)) {
                errors.add("YAML 声明的 Provider 没有实现 Bean：" + binding.key);
            }
        }
        for (ModelDocument.SpiBinding binding : source.resolvers) {
            if (!resolvers.containsKey(binding.key)) {
                errors.add("YAML 声明的 Resolver 没有实现 Bean：" + binding.key);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("扩展点注册不完整，服务拒绝启动：\n - " + String.join("\n - ", errors));
        }
    }

    private static <T> void putUnique(Map<String, T> map, String key, T value, String label) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(label + " 的 key 不可为空");
        }
        T previous = map.putIfAbsent(key, value);
        if (previous != null && previous != value) {
            throw new IllegalStateException(label + " 的 key 重复：" + key);
        }
    }

    public FieldMapping fieldMapping(String key) {
        FieldMapping mapping = fieldMappings.get(key);
        if (mapping == null) {
            throw new IllegalArgumentException("未注册的字段转换：" + key);
        }
        return mapping;
    }

    public RelationRule relationRule(String relationType) {
        RelationRule rule = relationRules.get(relationType);
        if (rule == null) {
            throw new IllegalArgumentException("未注册的关系规则：" + relationType);
        }
        return rule;
    }

    public DerivedPropertyProvider provider(String key) {
        DerivedPropertyProvider provider = providers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException("未注册的派生属性 Provider：" + key);
        }
        return provider;
    }

    public DynamicResolver resolver(String key) {
        return resolvers.get(key);
    }

    public boolean hasResolver(String key) {
        return resolvers.containsKey(key);
    }

    public List<Map<String, String>> providerBindings() {
        return List.copyOf(providerBindings);
    }

    public List<Map<String, String>> resolverBindings() {
        return List.copyOf(resolverBindings);
    }

    /** 供模型管理页展示已注册策略表（按 key 排序，纯数据）。 */
    public Map<String, Object> registrySummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fieldMappings", new ArrayList<>(fieldMappings.keySet()));
        summary.put("relationRules", new ArrayList<>(relationRules.keySet()));
        summary.put("providers", new ArrayList<>(providers.keySet()));
        summary.put("resolvers", new ArrayList<>(resolvers.keySet()));
        return summary;
    }
}

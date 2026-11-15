package com.school.ontology.core.projection;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.model.FieldMappingDef;
import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.spi.SourceRecord;
import com.school.ontology.core.spi.SpiRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 由 YAML 定义 + SPI 策略表把来源记录转成投影数据（对象 / 关系 / 来源引用）。
 *
 * <p>本类是「模型真正驱动运行时」的核心：字段列表、转换、派生属性、关系规则
 * 全部来自定义，没有任何 {@code if (objectType.equals(...))} 分支。新增对象类型
 * 只改 YAML 与 adapter，本类零改动。</p>
 */
@Component
public class SnapshotBuilder {

    private final ModelRegistry registry;
    private final SpiRegistry spi;

    public SnapshotBuilder(ModelRegistry registry, SpiRegistry spi) {
        this.registry = registry;
        this.spi = spi;
    }

    public SnapshotBatch buildAll(List<SourceRecord> records, String objectType, MappingContext ctx) {
        SnapshotBatch batch = SnapshotBatch.empty();
        for (SourceRecord record : records) {
            batch.merge(build(record, objectType, ctx));
        }
        return batch;
    }

    public SnapshotBatch build(SourceRecord rec, String objectType, MappingContext ctx) {
        ObjectTypeDef def = registry.typeOf(objectType);
        ObjectMappingDef mapping = registry.mappingOf(objectType);

        Map<String, Object> attributes = new LinkedHashMap<>();
        for (FieldMappingDef fm : mapping.fieldMappings) {
            AttributeDef runtimeDef = runtimeAttribute(def, fm);
            Object value;
            if (fm.transform == null || fm.transform.isBlank()) {
                value = Resp.normalize(rec.column(fm.source));
            } else {
                value = spi.fieldMapping(fm.transform).map(rec, runtimeDef, ctx);
            }
            attributes.put(fm.target, value);
        }
        // 派生属性：provider 在投影构建时算；resolver 按查询时间实时算，不落库。
        for (ObjectMappingDef.DerivedPropertyBinding binding : mapping.derivedProperties) {
            if (binding.provider == null || binding.provider.isBlank()) {
                continue;
            }
            DerivedPropertyDef derivedDef = derivedDefinition(def, binding.name);
            attributes.put(binding.name, spi.provider(binding.provider).compute(rec, derivedDef, ctx));
        }

        String globalId = GlobalIds.gid(ctx.sourceId(), objectType, rec.pk());
        String title = GlobalIds.render(def.titleTemplate, attributes);
        ProjectionRow row = new ProjectionRow(globalId, ctx.sourceId(), objectType, rec.pk(),
                title, attributes, ctx.mappingVersion(), ctx.revision(), false);

        List<RelationInstance> relations = new ArrayList<>();
        for (ObjectMappingDef.RelationRuleDef rule : mapping.relationRules) {
            RelationTypeDef relationDef = registry.relationOf(rule.relationType);
            relations.addAll(spi.relationRule(rule.relationType).derive(rec, relationDef, ctx));
        }
        for (ObjectMappingDef.JoinRelationRule rule : mapping.joinRelationRules) {
            RelationTypeDef relationDef = registry.relationOf(rule.relationType);
            relations.addAll(spi.relationRule(rule.relationType).derive(rec, relationDef, ctx));
        }

        SourceRefRow ref = new SourceRefRow(globalId, ctx.sourceId(), mapping.sourceTable,
                rec.pk(), rec.text("code"), rec.recordHash(), ctx.mappingVersion(), ctx.revision());

        List<ProjectionRow> objects = new ArrayList<>();
        objects.add(row);
        List<SourceRefRow> refs = new ArrayList<>();
        refs.add(ref);
        return new SnapshotBatch(objects, relations, refs);
    }

    /** 复制定义并注入本次字段映射的运行期参数，避免污染共享的 YAML 定义对象。 */
    private static AttributeDef runtimeAttribute(ObjectTypeDef def, FieldMappingDef fm) {
        AttributeDef source = def.attribute(fm.target);
        AttributeDef runtime = new AttributeDef();
        runtime.name = fm.target;
        runtime.type = source == null ? "string" : source.type;
        runtime.displayName = source == null ? fm.target : source.displayName;
        runtime.required = source != null && source.required;
        runtime.unique = source != null && source.unique;
        runtime.validate = source == null ? null : source.validate;
        runtime.from = source == null ? null : source.from;
        runtime.sourceColumn = fm.source;
        if (fm.lookup != null) {
            runtime.lookupTable = fm.lookup.table;
            runtime.lookupNameColumn = fm.lookup.nameColumn;
            runtime.lookupJoinColumn = fm.lookup.joinColumn;
            runtime.lookupNameTable = fm.lookup.nameTable;
        }
        return runtime;
    }

    private static DerivedPropertyDef derivedDefinition(ObjectTypeDef def, String name) {
        for (DerivedPropertyDef derived : def.derivedProperties) {
            if (derived != null && name.equals(derived.name)) {
                return derived;
            }
        }
        DerivedPropertyDef fallback = new DerivedPropertyDef();
        fallback.name = name;
        fallback.type = "string";
        fallback.displayName = name;
        return fallback;
    }
}

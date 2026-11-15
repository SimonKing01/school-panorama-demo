package com.school.ontology.core.mapping;

import com.school.ontology.core.model.GenerationRule;
import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.spi.RelationRule;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 通用联表关系规则：按定义给出的联表参数取关联键，生成 N:M 关系。
 * 联表查询由 LookupResolver（只读）承载，内核没有领域分支。
 */
@Component
public class JoinRelationRule implements RelationRule {

    private final ModelRegistry registry;

    public JoinRelationRule(ModelRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String relationType() {
        return "JOIN_RULE";
    }

    @Override
    public List<RelationInstance> derive(SourceRecord rec, RelationTypeDef def, MappingContext ctx) {
        GenerationRule generation = def.generation;
        Object filterValue = rec.column(generation.sourceColumn);
        if (filterValue == null || String.valueOf(filterValue).isBlank()) {
            return new ArrayList<>();
        }
        List<String> keys = ctx.lookups().joinKeys(generation.joinTable,
                generation.joinFilterColumn, filterValue, generation.joinSelectColumn);
        if (generation.distinct) {
            keys = new ArrayList<>(new LinkedHashSet<>(keys));
        }
        String targetType = registry.typeBySourceTable(generation.targetTable);
        String fromGlobalId = GlobalIds.gid(ctx.sourceId(), def.fromType, rec.pk());
        String reason = "联表规则 " + generation.rule + "（关系类型 " + def.id
                + "，映射版本 " + ctx.mappingVersion() + "）";
        List<RelationInstance> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (key == null || key.isBlank() || !seen.add(key)) {
                continue;
            }
            String toGlobalId = GlobalIds.gid(ctx.sourceId(), targetType, key);
            String relationId = GlobalIds.relationId(def.id, fromGlobalId, toGlobalId, null);
            result.add(new RelationInstance(relationId, def.id, fromGlobalId, toGlobalId,
                    reason, generation.joinTable, rec.pk(), rec.recordHash(),
                    ctx.mappingVersion(), null, null, true, ctx.revision()));
        }
        return result;
    }
}

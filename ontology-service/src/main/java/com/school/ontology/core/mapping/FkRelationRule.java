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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用外键关系规则：从来源记录的外键列生成一条关系实例。
 * 目标对象类型由「目标来源表 → 对象类型」反查得到，内核不认识任何业务类型。
 */
@Component
public class FkRelationRule implements RelationRule {

    private final ModelRegistry registry;

    public FkRelationRule(ModelRegistry registry) {
        this.registry = registry;
    }

    /** 按生成规则 kind 注册，SpiRegistry 再按定义把关系类型 id 映射到本实现。 */
    @Override
    public String relationType() {
        return "FOREIGN_KEY";
    }

    @Override
    public List<RelationInstance> derive(SourceRecord rec, RelationTypeDef def, MappingContext ctx) {
        GenerationRule generation = def.generation;
        Object fkValue = rec.column(generation.fkColumn);
        if (fkValue == null || String.valueOf(fkValue).isBlank()) {
            return new ArrayList<>();
        }
        String targetType = registry.typeBySourceTable(generation.targetTable);
        String fromGlobalId = GlobalIds.gid(ctx.sourceId(), def.fromType, rec.pk());
        String toGlobalId = GlobalIds.gid(ctx.sourceId(), targetType, fkValue);
        LocalDateTime validFrom = def.businessEffectiveTime
                ? RelationTimes.validFrom(rec, generation.validFromColumns) : null;
        String reason = "外键 " + generation.sourceTable + "." + generation.fkColumn
                + " → " + generation.targetTable + "（关系类型 " + def.id
                + "，映射版本 " + ctx.mappingVersion() + "）";
        String relationId = GlobalIds.relationId(def.id, fromGlobalId, toGlobalId, validFrom);
        RelationInstance instance = new RelationInstance(relationId, def.id,
                fromGlobalId, toGlobalId, reason, generation.sourceTable, rec.pk(),
                rec.recordHash(), ctx.mappingVersion(), validFrom, null, true, ctx.revision());
        List<RelationInstance> result = new ArrayList<>();
        result.add(instance);
        return result;
    }
}

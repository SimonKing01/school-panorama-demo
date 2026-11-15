package com.school.ontology.core.mapping;

import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.spi.FieldMapping;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

/** LOOKUP_NAME：外键 id → 目标表名称列，参数全部来自 YAML 的 lookup 定义。 */
@Component
public class LookupNameMapping implements FieldMapping {

    @Override
    public String key() {
        return "LOOKUP_NAME";
    }

    @Override
    public Object map(SourceRecord rec, AttributeDef def, MappingContext ctx) {
        Object key = rec.column(def.sourceColumn);
        if (key == null || def.lookupTable == null) {
            return null;
        }
        return ctx.lookups().name(def.lookupTable, def.lookupNameColumn, key);
    }
}

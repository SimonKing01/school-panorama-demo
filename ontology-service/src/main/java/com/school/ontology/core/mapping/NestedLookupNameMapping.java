package com.school.ontology.core.mapping;

import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.spi.FieldMapping;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

/** NESTED_LOOKUP_NAME：两级外键解引用（如 students.class_id → classes.school_id → schools.name）。 */
@Component
public class NestedLookupNameMapping implements FieldMapping {

    @Override
    public String key() {
        return "NESTED_LOOKUP_NAME";
    }

    @Override
    public Object map(SourceRecord rec, AttributeDef def, MappingContext ctx) {
        Object key = rec.column(def.sourceColumn);
        if (key == null || def.lookupTable == null) {
            return null;
        }
        String nextKey = ctx.lookups().foreignKey(def.lookupTable, def.lookupJoinColumn, key);
        if (nextKey == null) {
            return null;
        }
        return ctx.lookups().name(def.lookupNameTable, def.lookupNameColumn, nextKey);
    }
}

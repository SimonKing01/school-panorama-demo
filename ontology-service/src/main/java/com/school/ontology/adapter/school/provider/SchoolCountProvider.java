package com.school.ontology.adapter.school.provider;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 学校班级数 / 教师数：按派生属性名查表（学校领域规则集中在适配层，内核无分支）。 */
@Component
public class SchoolCountProvider implements DerivedPropertyProvider {

    private static final Map<String, String> COUNT_TABLE = Map.of(
            "classCount", "classes",
            "teacherCount", "teachers");

    @Override
    public String key() {
        return "schoolCountProvider";
    }

    @Override
    public Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx) {
        String table = COUNT_TABLE.get(def.name);
        if (table == null) {
            return 0L;
        }
        return ctx.lookups().count(table, "school_id", rec.pk());
    }
}

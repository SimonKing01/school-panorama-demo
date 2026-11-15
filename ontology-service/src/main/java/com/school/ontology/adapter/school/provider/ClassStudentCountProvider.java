package com.school.ontology.adapter.school.provider;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

/** 班级学生人数：班级对象用自身主键，授课安排对象用 class_id（由定义 inputs 说明）。 */
@Component
public class ClassStudentCountProvider implements DerivedPropertyProvider {

    @Override
    public String key() {
        return "classStudentCountProvider";
    }

    @Override
    public Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx) {
        Object classId = rec.columns().containsKey("class_id") ? rec.column("class_id") : rec.pk();
        return ctx.lookups().count("students", "class_id", classId);
    }
}

package com.school.ontology.adapter.school.provider;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 教师任教班级：由课表反查（联表/明确规则），不建立教师-班级冗余关联表。 */
@Component
public class TeacherClassProvider implements DerivedPropertyProvider {

    @Override
    public String key() {
        return "teacherClassProvider";
    }

    @Override
    public Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx) {
        List<String> classIds = ctx.lookups().joinKeys("lesson_sessions", "teacher_id",
                rec.pk(), "class_id");
        Set<String> names = new LinkedHashSet<>();
        for (String classId : classIds) {
            String name = ctx.lookups().name("classes", "name", classId);
            if (name != null) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }
}

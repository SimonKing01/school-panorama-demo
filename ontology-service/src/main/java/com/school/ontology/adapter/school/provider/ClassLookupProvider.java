package com.school.ontology.adapter.school.provider;

import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.spi.DerivedPropertyProvider;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

/** 联表派生属性样例：学生所在班级名称由 class_id 解引用得到（B4 要求有真实联表样例）。 */
@Component
public class ClassLookupProvider implements DerivedPropertyProvider {

    @Override
    public String key() {
        return "classLookupProvider";
    }

    @Override
    public Object compute(SourceRecord rec, DerivedPropertyDef def, MappingContext ctx) {
        return ctx.lookups().name("classes", "name", rec.column("class_id"));
    }
}

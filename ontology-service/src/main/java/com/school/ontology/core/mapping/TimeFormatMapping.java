package com.school.ontology.core.mapping;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.spi.FieldMapping;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/** TIME_HHmm：把来源时间列统一成 HH:mm 字符串。 */
@Component
public class TimeFormatMapping implements FieldMapping {

    @Override
    public String key() {
        return "TIME_HHmm";
    }

    @Override
    public Object map(SourceRecord rec, AttributeDef def, MappingContext ctx) {
        Object raw = rec.column(def.sourceColumn);
        if (raw == null) {
            return null;
        }
        Object normalized = Resp.normalize(raw);
        if (normalized instanceof LocalTime time) {
            return Resp.hm(time);
        }
        String text = String.valueOf(normalized);
        try {
            return Resp.hm(LocalTime.parse(text.length() > 5 ? text.substring(0, 5) : text));
        } catch (Exception ignored) {
            return text;
        }
    }
}

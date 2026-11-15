package com.school.ontology.core.mapping;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.spi.FieldMapping;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** DATE_yyyyMMdd：把来源日期列统一成 yyyy-MM-dd 字符串（演示本地时间口径）。 */
@Component
public class DateFormatMapping implements FieldMapping {

    @Override
    public String key() {
        return "DATE_yyyyMMdd";
    }

    @Override
    public Object map(SourceRecord rec, AttributeDef def, MappingContext ctx) {
        Object raw = rec.column(def.sourceColumn);
        if (raw == null) {
            return null;
        }
        Object normalized = Resp.normalize(raw);
        if (normalized instanceof LocalDate date) {
            return Resp.d(date);
        }
        String text = String.valueOf(normalized);
        try {
            return Resp.d(LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text));
        } catch (Exception ignored) {
            // 来源列已是目标格式或无法解析时原样返回，由详情展示层兜底。
            return text;
        }
    }
}

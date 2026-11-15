package com.school.ontology.core.mapping;

import com.school.ontology.core.spi.SourceRecord;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 关系业务有效起点：由定义给出的来源列推导，内核不内含任何领域概念。 */
final class RelationTimes {

    private RelationTimes() {
    }

    static LocalDateTime validFrom(SourceRecord rec, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        Object dateValue = rec.column(columns.get(0));
        if (dateValue == null) {
            return null;
        }
        LocalDate date;
        try {
            String text = String.valueOf(dateValue);
            date = LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
        } catch (Exception e) {
            return null;
        }
        LocalTime time = LocalTime.MIN;
        if (columns.size() > 1) {
            Object timeValue = rec.column(columns.get(1));
            if (timeValue != null) {
                try {
                    String text = String.valueOf(timeValue);
                    time = LocalTime.parse(text.length() > 5 ? text.substring(0, 5) : text);
                } catch (Exception ignored) {
                    time = LocalTime.MIN;
                }
            }
        }
        return LocalDateTime.of(date, time);
    }
}

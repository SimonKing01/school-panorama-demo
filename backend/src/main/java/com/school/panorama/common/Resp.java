package com.school.panorama.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 响应组装与时间格式化的共用小工具。 */
public final class Resp {

    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Resp() {
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static String ts(LocalDateTime t) {
        return t == null ? null : t.format(TS);
    }

    public static String hm(LocalTime t) {
        return t == null ? null : t.format(HM);
    }

    public static String d(LocalDate date) {
        return date == null ? null : date.format(D);
    }

    public static LocalDateTime parseTs(String s) {
        return s == null ? null : LocalDateTime.parse(s, TS);
    }

    public static List<Map<String, Object>> emptyConflicts() {
        return List.of();
    }
}

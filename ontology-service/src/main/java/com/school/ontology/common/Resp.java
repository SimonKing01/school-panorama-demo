package com.school.ontology.common;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 本体服务的响应组装、时间格式化与来源列值归一化小工具。 */
public final class Resp {

    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    public static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    public static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Resp() {
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return result;
    }

    public static String ts(LocalDateTime value) {
        return value == null ? null : value.format(TS);
    }

    public static String hm(LocalTime value) {
        return value == null ? null : value.format(HM);
    }

    public static String d(LocalDate value) {
        return value == null ? null : value.format(D);
    }

    public static LocalDateTime parseTs(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value, TS);
    }

    /**
     * 把 JDBC 取回的异构列值转成可直接序列化进 JSONB 的 Java 值。
     * 演示时间按本地时间解释，不做时区换算（AGENTS.md 时间口径）。
     */
    public static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().format(D);
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().format(HM);
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(TS);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(TS);
        }
        if (value instanceof LocalDate date) {
            return date.format(D);
        }
        if (value instanceof LocalTime time) {
            return time.format(HM);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value;
    }

    /** 稳定哈希：用于来源记录指纹与映射版本，输入顺序无关（调用方保证有序拼接）。 */
    public static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("运行环境缺少 SHA-256 算法，无法计算稳定版本/指纹", e);
        }
    }
}

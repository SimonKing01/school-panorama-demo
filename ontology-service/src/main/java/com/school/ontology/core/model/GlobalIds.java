package com.school.ontology.core.model;

import com.school.ontology.common.Resp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 稳定全局 ID 与来源指纹的生成规则（B3 硬性）。 */
public final class GlobalIds {

    public static final String GID_RULE = "gid:{sourceId}:{objectType}:{sourcePk}";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    private GlobalIds() {
    }

    /**
     * {@code gid:{sourceId}:{objectType}:{sourcePk}}。
     * 不含名称、不含来源 code；不同数据源即使主键相同也落在不同 globalId，禁止跨源合并。
     */
    public static String gid(String sourceId, String objectType, Object sourcePk) {
        if (sourceId == null || sourceId.isBlank()
                || objectType == null || objectType.isBlank()
                || sourcePk == null || String.valueOf(sourcePk).isBlank()) {
            throw new IllegalArgumentException("全局 ID 的 sourceId/objectType/sourcePk 均不可为空");
        }
        return "gid:" + sourceId + ":" + objectType + ":" + String.valueOf(sourcePk);
    }

    /** 关系稳定标识：关系类型 + 两端 + 业务有效起点，保证重放/重建不产生重复关系。 */
    public static String relationId(String relationType, String fromGlobalId, String toGlobalId,
                                    LocalDateTime validFrom) {
        return relationType + ":" + fromGlobalId + ":" + toGlobalId + ":"
                + (validFrom == null ? "NA" : validFrom.format(Resp.TS));
    }

    /** 标题模板渲染：{code} · {courseName} · {className}，缺失字段留空。 */
    public static String render(String template, Map<String, Object> attributes) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        while (matcher.find()) {
            builder.append(template, cursor, matcher.start());
            Object value = attributes.get(matcher.group(1));
            builder.append(value == null ? "" : String.valueOf(value));
            cursor = matcher.end();
        }
        builder.append(template.substring(cursor));
        return builder.toString().trim();
    }

    /** 来源记录指纹：按列名排序后拼接，保证同一记录每次哈希稳定。 */
    public static String recordHash(Map<String, Object> columns) {
        List<String> keys = new ArrayList<>(columns.keySet());
        Collections.sort(keys);
        StringBuilder builder = new StringBuilder();
        for (String key : keys) {
            builder.append(key).append('=').append(String.valueOf(columns.get(key))).append(';');
        }
        return Resp.sha256(builder.toString());
    }
}

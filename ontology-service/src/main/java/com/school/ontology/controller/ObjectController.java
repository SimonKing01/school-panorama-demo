package com.school.ontology.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.ontology.common.ApiException;
import com.school.ontology.common.OntApiResponse;
import com.school.ontology.common.Resp;
import com.school.ontology.core.query.OntologyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 通用对象探索器 + 全局直达搜索；字段/列/可搜索项全部来自模型定义。 */
@RestController
@RequestMapping("/api/ontology")
public class ObjectController {

    /** 控制参数，绝不能被当成筛选字段（契约 03 §2.6 的 type/q/filters/page/size/queryTime）。 */
    private static final Set<String> CONTROL_PARAMS =
            Set.of("type", "q", "filters", "page", "size", "queryTime");

    private final OntologyQueryService query;
    private final ObjectMapper objectMapper;

    public ObjectController(OntologyQueryService query, ObjectMapper objectMapper) {
        this.query = query;
        this.objectMapper = objectMapper;
    }

    /**
     * 对象列表。
     *
     * <p>{@code params} 收全部 query 参数：契约 03 §2.6 允许「按 filterableFields 平铺」，
     * 而只声明 {@code filters} 时 Spring 会静默丢弃平铺参数（{@code ?type=student&gender=男}
     * 完全不生效），因此必须自己把平铺参数接进来，见 {@link #mergeFilters}。</p>
     */
    @GetMapping("/objects")
    public OntApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String filters,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String queryTime,
            @RequestParam Map<String, String> params) {
        if (page < 1 || size < 1 || size > 200) {
            throw ApiException.badRequest("page 必须 ≥1，size 必须在 1..200 之间");
        }
        return OntApiResponse.ok(query.listObjects(type, q, mergeFilters(type, filters, params),
                page, size, query.resolveQueryTime(parseQueryTime(queryTime))));
    }

    @GetMapping("/objects/{globalId}")
    public OntApiResponse<Map<String, Object>> detail(
            @PathVariable String globalId,
            @RequestParam(required = false) String queryTime) {
        return OntApiResponse.ok(query.detail(globalId,
                query.resolveQueryTime(parseQueryTime(queryTime))));
    }

    @GetMapping("/search")
    public OntApiResponse<Map<String, Object>> search(@RequestParam(required = false) String q) {
        return OntApiResponse.ok(query.search(q));
    }

    /**
     * 合并两种筛选写法（契约 03 §2.6：「{@code ?filters=<JSON>}」或「按类型 filterableFields 平铺」）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>只接受该 type 的 {@code filterableFields} 里声明过的键，未知键照旧忽略（不报错，
     *       避免调用方传错字段就 500）；</li>
     *   <li>{@code type/q/filters/page/size/queryTime} 是控制参数，绝不能当筛选字段；</li>
     *   <li>type 为空（全局列表）时无从确定可筛字段，平铺参数一律忽略（安全路径）；</li>
     *   <li>同名键以 JSON 写法为准，平铺值不覆盖 —— JSON 是契约写法一，语义更明确。</li>
     * </ul>
     */
    private Map<String, String> mergeFilters(String type, String filters, Map<String, String> params) {
        Map<String, String> merged = new LinkedHashMap<>(parseFilters(filters));
        if (type == null || type.isBlank() || params == null || params.isEmpty()) {
            return merged;
        }
        Set<String> allowed = query.filterableFieldsOf(type);
        if (allowed.isEmpty()) {
            return merged;
        }
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || CONTROL_PARAMS.contains(key) || !allowed.contains(key)
                    || merged.containsKey(key)) {
                continue;
            }
            merged.put(key, entry.getValue());
        }
        return merged;
    }

    private Map<String, String> parseFilters(String filters) {
        Map<String, String> result = new LinkedHashMap<>();
        if (filters == null || filters.isBlank()) {
            return result;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(filters,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (entry.getValue() != null) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        } catch (Exception e) {
            throw ApiException.badRequest("filters 必须是 JSON 对象字符串");
        }
        return result;
    }

    private LocalDateTime parseQueryTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Resp.parseTs(value);
        } catch (Exception e) {
            throw ApiException.badRequest("queryTime 格式应为 yyyy-MM-dd'T'HH:mm:ss");
        }
    }
}

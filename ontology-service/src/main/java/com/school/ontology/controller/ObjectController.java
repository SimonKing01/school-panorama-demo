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

/** 通用对象探索器 + 全局直达搜索；字段/列/可搜索项全部来自模型定义。 */
@RestController
@RequestMapping("/api/ontology")
public class ObjectController {

    private final OntologyQueryService query;
    private final ObjectMapper objectMapper;

    public ObjectController(OntologyQueryService query, ObjectMapper objectMapper) {
        this.query = query;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/objects")
    public OntApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String filters,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String queryTime) {
        if (page < 1 || size < 1 || size > 200) {
            throw ApiException.badRequest("page 必须 ≥1，size 必须在 1..200 之间");
        }
        return OntApiResponse.ok(query.listObjects(type, q, parseFilters(filters), page, size,
                query.resolveQueryTime(parseQueryTime(queryTime))));
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

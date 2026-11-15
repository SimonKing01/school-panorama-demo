package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.ChangeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 变化与影响：双侧可查同一 changeId，区分本身变化与计算出的相关对象。 */
@RestController
@RequestMapping("/api/ontology")
public class ChangeController {

    private final ChangeQueryService changeQuery;

    public ChangeController(ChangeQueryService changeQuery) {
        this.changeQuery = changeQuery;
    }

    @GetMapping("/changes")
    public OntApiResponse<Map<String, Object>> changes(
            @RequestParam(required = false) String globalId,
            @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OntApiResponse.ok(changeQuery.changes(globalId, role, page, size));
    }

    @GetMapping("/changes/{changeId}")
    public OntApiResponse<Map<String, Object>> changeDetail(@PathVariable long changeId) {
        return OntApiResponse.ok(changeQuery.changeDetail(changeId));
    }
}

package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 数据源状态与新鲜度：五态判定 + 来源不可用标记 + 两类时间分开。 */
@RestController
@RequestMapping("/api/ontology/sources")
public class SourceStatusController {

    private final OverviewService overviewService;

    public SourceStatusController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/status")
    public OntApiResponse<Map<String, Object>> status() {
        return OntApiResponse.ok(overviewService.sourceStatus());
    }
}

package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 全景总览：对象/关系规模、数据源状态、同步状态、近期变化与影响摘要。 */
@RestController
@RequestMapping("/api/ontology")
public class OverviewController {

    private final OverviewService overviewService;

    public OverviewController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public OntApiResponse<Map<String, Object>> overview() {
        return OntApiResponse.ok(overviewService.overview());
    }
}

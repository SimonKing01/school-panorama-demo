package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.OverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 前端短轮询端点：平台版本只增不减，刷新全部视角成功后才 ack。 */
@RestController
@RequestMapping("/api/ontology")
public class RevisionController {

    private final OverviewService overviewService;

    public RevisionController(OverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/revision")
    public OntApiResponse<Map<String, Object>> revision() {
        return OntApiResponse.ok(overviewService.revision());
    }
}

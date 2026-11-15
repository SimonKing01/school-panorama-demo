package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.OntologyQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 模型与接入管理只读端点：定义真正驱动前端渲染（B1/B5/E5）。 */
@RestController
@RequestMapping("/api/ontology/model")
public class ModelController {

    private final OntologyQueryService query;

    public ModelController(OntologyQueryService query) {
        this.query = query;
    }

    @GetMapping("/types")
    public OntApiResponse<Map<String, Object>> types() {
        return OntApiResponse.ok(query.modelTypes());
    }

    @GetMapping("/relations")
    public OntApiResponse<Map<String, Object>> relations() {
        return OntApiResponse.ok(query.modelRelations());
    }

    @GetMapping("/mappings")
    public OntApiResponse<Map<String, Object>> mappings() {
        return OntApiResponse.ok(query.modelMappings());
    }
}

package com.school.ontology.controller;

import com.school.ontology.common.OntApiResponse;
import com.school.ontology.core.query.GraphService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 关系探索：列表 + 关系图（1/2 层、节点上限、点边解释成因）。 */
@RestController
@RequestMapping("/api/ontology/objects")
public class RelationController {

    private final GraphService graphService;

    public RelationController(GraphService graphService) {
        this.graphService = graphService;
    }

    @GetMapping("/{globalId}/relations")
    public OntApiResponse<Map<String, Object>> relations(
            @PathVariable String globalId,
            @RequestParam(required = false, defaultValue = "both") String direction,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OntApiResponse.ok(graphService.relations(globalId, direction, depth, page, size));
    }

    @GetMapping("/{globalId}/graph")
    public OntApiResponse<Map<String, Object>> graph(
            @PathVariable String globalId,
            @RequestParam(defaultValue = "1") int depth,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "1") int page) {
        return OntApiResponse.ok(graphService.graph(globalId, depth, limit, page));
    }
}

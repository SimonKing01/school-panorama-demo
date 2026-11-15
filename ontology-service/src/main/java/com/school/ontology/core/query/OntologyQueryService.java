package com.school.ontology.core.query;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.model.MappingVersion;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelDocument;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.model.RelationTypeDef;
import com.school.ontology.core.spi.DynamicResult;
import com.school.ontology.core.spi.SpiRegistry;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用对象查询服务：类型列表、对象列表、详情、全局搜索全部由模型定义驱动。
 * 详情字段、列表列、可搜索项、动态结果都来自定义与策略表，没有类型专用分支。
 */
@Component
public class OntologyQueryService {

    private final OntologyRepository repository;
    private final ModelRegistry registry;
    private final ModelDefinitionLoader loader;
    private final SpiRegistry spi;
    private final DynamicResolverDispatcher dispatcher;
    private final GraphService graphService;
    private final ChangeQueryService changeQuery;
    private final OverviewService overviewService;
    private final String businessWebBase;

    public OntologyQueryService(OntologyRepository repository, ModelRegistry registry,
                                ModelDefinitionLoader loader, SpiRegistry spi,
                                DynamicResolverDispatcher dispatcher, GraphService graphService,
                                ChangeQueryService changeQuery, OverviewService overviewService,
                                @Value("${ontology.business-web-base:http://localhost:5180}")
                                String businessWebBase) {
        this.repository = repository;
        this.registry = registry;
        this.loader = loader;
        this.spi = spi;
        this.dispatcher = dispatcher;
        this.graphService = graphService;
        this.changeQuery = changeQuery;
        this.overviewService = overviewService;
        this.businessWebBase = businessWebBase;
    }

    /** GET /model/types：驱动前端类型列表、列表列、详情字段、可搜索/可筛选项。 */
    public Map<String, Object> modelTypes() {
        MappingVersion version = loader.mappingVersion();
        List<Map<String, Object>> types = new ArrayList<>();
        for (ObjectTypeDef def : registry.types()) {
            List<Map<String, Object>> listFields = new ArrayList<>();
            for (String field : def.listFields) {
                listFields.add(fieldView(def, field));
            }
            List<Map<String, Object>> detailFields = new ArrayList<>();
            for (AttributeDef attr : def.attributes) {
                detailFields.add(Resp.map("name", attr.name, "displayName", attr.displayName,
                        "type", attr.type, "origin", "SOURCE", "from", attr.from));
            }
            for (DerivedPropertyDef derived : def.derivedProperties) {
                detailFields.add(Resp.map("name", derived.name, "displayName", derived.displayName,
                        "type", derived.type, "origin", "DERIVED",
                        "provider", derived.provider, "resolver", derived.resolver));
            }
            List<Map<String, Object>> relations = new ArrayList<>();
            for (RelationTypeDef relation : registry.relations()) {
                if (relation.fromType.equals(def.id) || relation.toType.equals(def.id)) {
                    relations.add(Resp.map("id", relation.id,
                            "forwardName", relation.forwardName,
                            "reverseName", relation.reverseName,
                            "fromType", relation.fromType, "toType", relation.toType,
                            "cardinality", relation.cardinality));
                }
            }
            ObjectMappingDef mapping = registry.mappingOf(def.id);
            types.add(Resp.map(
                    "id", def.id,
                    "displayName", def.displayName,
                    "description", def.description,
                    "titleTemplate", def.titleTemplate,
                    "listFields", listFields,
                    "detailFields", detailFields,
                    "searchableFields", def.searchableFields,
                    "filterableFields", def.filterableFields,
                    "derivedProperties", def.derivedProperties.stream()
                            .map(d -> Resp.map("name", d.name, "provider", d.provider,
                                    "resolver", d.resolver)).toList(),
                    "dynamicResolvers", def.dynamicResolvers,
                    "relations", relations,
                    "sourceMapping", Resp.map("sourceId", def.identity.sourceId,
                            "sourceTable", mapping.sourceTable, "sourcePk", mapping.pk)));
        }
        return Resp.map("mappingVersion", version.mappingVersion, "types", types);
    }

    public Map<String, Object> modelRelations() {
        List<Map<String, Object>> relations = new ArrayList<>();
        for (RelationTypeDef def : registry.relations()) {
            relations.add(Resp.map("id", def.id, "displayName", def.displayName,
                    "fromType", def.fromType, "toType", def.toType,
                    "forwardName", def.forwardName, "reverseName", def.reverseName,
                    "direction", def.direction, "cardinality", def.cardinality,
                    "businessEffectiveTime", def.businessEffectiveTime,
                    "generation", Resp.map("kind", def.generation.kind,
                            "sourceTable", def.generation.sourceTable,
                            "fkColumn", def.generation.fkColumn,
                            "targetTable", def.generation.targetTable,
                            "rule", def.generation.rule),
                    "sourceMappingRef", def.sourceMappingRef));
        }
        return Resp.map("mappingVersion", loader.mappingVersion().mappingVersion,
                "relations", relations);
    }

    public Map<String, Object> modelMappings() {
        ModelDocument.DataSourceDef source = loader.sourceDocument().dataSource;
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (Map.Entry<String, ObjectMappingDef> entry : loader.sourceDocument().objectMappings.entrySet()) {
            ObjectMappingDef mapping = entry.getValue();
            mappings.add(Resp.map("objectType", entry.getKey(),
                    "sourceTable", mapping.sourceTable,
                    "fieldMappings", mapping.fieldMappings.stream()
                            .map(f -> Resp.map("target", f.target, "source", f.source,
                                    "transform", f.transform)).toList(),
                    "relationRules", mapping.relationRules.stream()
                            .map(r -> Resp.map("relationType", r.relationType,
                                    "fk", r.fk, "targetObjectType", r.targetObjectType)).toList(),
                    "joinRelationRules", mapping.joinRelationRules.stream()
                            .map(r -> Resp.map("relationType", r.relationType, "rule", r.rule)).toList(),
                    "stableIdRule", mapping.stableId == null ? null : mapping.stableId.rule));
        }
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Map<String, Object> row : repository.mappingVersions(source.id)) {
            versions.add(Resp.map("sourceId", OntologyRepository.str(row.get("source_id")),
                    "mappingVersion", OntologyRepository.str(row.get("mapping_version")),
                    "definitionHash", OntologyRepository.str(row.get("definition_hash")),
                    "activatedAt", Resp.ts(OntologyRepository.dateTime(row.get("activated_at"))),
                    "status", OntologyRepository.str(row.get("status"))));
        }
        return Resp.map(
                "dataSources", List.of(Resp.map("id", source.id,
                        "displayName", source.displayName, "type", source.type,
                        "watermarkKind", source.watermark.kind, "status", "ACTIVE")),
                "objectMappings", mappings,
                "versions", versions,
                "providers", spi.providerBindings(),
                "resolvers", spi.resolverBindings(),
                "registry", spi.registrySummary());
    }

    public Map<String, Object> listObjects(String type, String q, Map<String, String> filters,
                                           int page, int size, LocalDateTime queryTime) {
        List<String> searchFields = searchFieldsOf(type);
        long total = repository.countObjects(type, searchFields, q, filters);
        List<Map<String, Object>> rows = repository.listObjects(type, searchFields, q, filters,
                page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(objectView(row, queryTime, false));
        }
        return Resp.map("type", type, "page", page, "size", size,
                "total", total, "items", items);
    }

    public Map<String, Object> detail(String globalId, LocalDateTime queryTime) {
        Map<String, Object> row = repository.objectRow(globalId);
        if (row.isEmpty()) {
            throw ApiException.notFound("对象不存在：" + globalId);
        }
        String objectType = OntologyRepository.str(row.get("object_type"));
        ObjectTypeDef def = registry.typeOf(objectType);
        Map<String, Object> attributes = repository.parseMap(row.get("attributes"));
        List<Map<String, Object>> attributeViews = new ArrayList<>();
        for (AttributeDef attr : def.attributes) {
            attributeViews.add(Resp.map("name", attr.name, "displayName", attr.displayName,
                    "value", attributes.get(attr.name), "origin", "SOURCE"));
        }
        for (DerivedPropertyDef derived : def.derivedProperties) {
            attributeViews.add(Resp.map("name", derived.name, "displayName", derived.displayName,
                    "value", attributes.get(derived.name), "origin", "DERIVED",
                    "provider", derived.provider, "resolver", derived.resolver));
        }
        List<DynamicResult> dynamicResults = dispatcher.resolveAll(globalId, objectType, queryTime);
        List<Map<String, Object>> forward = new ArrayList<>();
        for (var relation : repository.relationsFrom(globalId, true)) {
            forward.add(graphService.relationView(relation));
        }
        List<Map<String, Object>> reverse = new ArrayList<>();
        for (var relation : repository.relationsTo(globalId, true)) {
            reverse.add(graphService.relationView(relation));
        }
        Map<String, Object> source = new LinkedHashMap<>();
        for (var ref : repository.sourceRefsOf(globalId)) {
            source = Resp.map("sourceId", ref.sourceId(), "sourceTable", ref.sourceTable(),
                    "sourcePk", ref.sourcePk(), "sourceCode", ref.sourceCode(),
                    "sourceHash", ref.sourceHash(), "mappingVersion", ref.mappingVersion());
            break;
        }
        Map<String, Object> recent = changeQuery.changes(globalId, null, 1, 5);
        return Resp.map(
                "globalId", globalId,
                "type", objectType,
                "title", OntologyRepository.str(row.get("title")),
                "mappingVersion", OntologyRepository.str(row.get("mapping_version")),
                "attributes", attributeViews,
                "dynamicResults", dynamicResults,
                "relations", Resp.map("forward", forward, "reverse", reverse),
                "recentChanges", recent.get("items"),
                "source", source,
                "backToBusinessUrl", businessWebBase + "/objects?type=" + objectType);
    }

    public Map<String, Object> search(String q) {
        if (q == null || q.isBlank()) {
            return Resp.map("q", q, "total", 0, "items", List.of());
        }
        Map<String, List<String>> searchableByType = new LinkedHashMap<>();
        for (ObjectTypeDef def : registry.types()) {
            searchableByType.put(def.id, def.searchableFields);
        }
        List<Map<String, Object>> rows = repository.search(q, searchableByType, 50);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> attributes = repository.parseMap(row.get("attributes"));
            String matchedField = "";
            String highlight = "";
            for (String field : searchableByType.getOrDefault(
                    OntologyRepository.str(row.get("object_type")), List.of())) {
                Object value = attributes.get(field);
                if (value != null && String.valueOf(value).contains(q)) {
                    matchedField = field;
                    highlight = String.valueOf(value).replace(q, "<em>" + q + "</em>");
                    break;
                }
            }
            items.add(Resp.map(
                    "globalId", OntologyRepository.str(row.get("global_id")),
                    "type", OntologyRepository.str(row.get("object_type")),
                    "title", OntologyRepository.str(row.get("title")),
                    "matchedField", matchedField, "highlight", highlight,
                    "attributes", attributes,
                    "sourceSummary", sourceSummary(OntologyRepository.str(row.get("global_id")))));
        }
        return Resp.map("q", q, "total", items.size(), "items", items);
    }

    /** queryTime 缺省时取底座 demoTime 投影（可回拨），绝不取真实处理时间。 */
    public LocalDateTime resolveQueryTime(LocalDateTime requested) {
        if (requested != null) {
            return requested;
        }
        Map<String, Object> ds = repository.dataSource(registry.sourceId());
        return overviewService.currentDemoTime(ds);
    }

    private Map<String, Object> objectView(Map<String, Object> row, LocalDateTime queryTime,
                                           boolean withRelations) {
        String globalId = OntologyRepository.str(row.get("global_id"));
        String objectType = OntologyRepository.str(row.get("object_type"));
        Map<String, Object> attributes = repository.parseMap(row.get("attributes"));
        Map<String, Object> view = Resp.map(
                "globalId", globalId,
                "type", objectType,
                "title", OntologyRepository.str(row.get("title")),
                "attributes", attributes,
                "dynamicResults", dispatcher.resolveAll(globalId, objectType, queryTime),
                "sourceSummary", sourceSummary(globalId));
        if (withRelations) {
            view.put("relations", List.of());
        }
        return view;
    }

    private Map<String, Object> sourceSummary(String globalId) {
        for (var ref : repository.sourceRefsOf(globalId)) {
            return Resp.map("sourceId", ref.sourceId(), "sourceTable", ref.sourceTable(),
                    "sourcePk", ref.sourcePk(), "sourceCode", ref.sourceCode(),
                    "mappingVersion", ref.mappingVersion());
        }
        return Map.of();
    }

    private List<String> searchFieldsOf(String type) {
        Set<String> fields = new LinkedHashSet<>();
        if (type == null || type.isBlank()) {
            for (ObjectTypeDef def : registry.types()) {
                fields.addAll(def.searchableFields);
            }
        } else {
            fields.addAll(registry.typeOf(type).searchableFields);
        }
        return new ArrayList<>(fields);
    }

    private Map<String, Object> fieldView(ObjectTypeDef def, String field) {
        AttributeDef attr = def.attribute(field);
        if (attr != null) {
            return Resp.map("name", attr.name, "displayName", attr.displayName, "type", attr.type);
        }
        for (DerivedPropertyDef derived : def.derivedProperties) {
            if (field.equals(derived.name)) {
                return Resp.map("name", derived.name, "displayName", derived.displayName,
                        "type", derived.type, "derived", true);
            }
        }
        return Resp.map("name", field, "displayName", field, "type", "string");
    }
}

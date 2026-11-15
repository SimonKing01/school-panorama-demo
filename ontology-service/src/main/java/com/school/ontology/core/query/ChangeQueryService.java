package com.school.ontology.core.query;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 变化与影响查询：属性前后差异 / 关系增减 / 来源事件 / 两类时间 / 双侧可查。
 * role 由 ont_change_impact 决定：SELF_CHANGED（本身变化）与
 * DERIVED_RELATED（按规则计算出的相关对象），避免把邻接对象都标成业务状态已改变。
 */
@Component
public class ChangeQueryService {

    private final OntologyRepository repository;

    public ChangeQueryService(OntologyRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> changes(String globalId, String role, int page, int size) {
        int offset = Math.max(page - 1, 0) * size;
        List<Map<String, Object>> rows = repository.changes(globalId, role, size, offset);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(toView(row, false));
        }
        return Resp.map("page", page, "size", size,
                "total", repository.countChanges(globalId, role), "items", items);
    }

    public Map<String, Object> changeDetail(long changeId) {
        Map<String, Object> row = repository.changeById(changeId);
        if (row.isEmpty()) {
            throw ApiException.notFound("变化记录不存在：" + changeId);
        }
        Map<String, Object> view = toView(row, true);
        view.put("beforeJson", repository.parseMap(row.get("before_json")));
        view.put("afterJson", repository.parseMap(row.get("after_json")));
        return view;
    }

    private Map<String, Object> toView(Map<String, Object> row, boolean detail) {
        long changeId = OntologyRepository.lng(row.get("change_id"));
        String globalId = OntologyRepository.str(row.get("global_id"));
        List<Map<String, Object>> impacts = new ArrayList<>();
        for (Map<String, Object> impact : repository.impactsOf(changeId)) {
            impacts.add(Resp.map("globalId", OntologyRepository.str(impact.get("global_id")),
                    "role", OntologyRepository.str(impact.get("role")),
                    "title", titleOf(OntologyRepository.str(impact.get("global_id"))),
                    "basis", OntologyRepository.str(impact.get("basis"))));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("changeId", changeId);
        view.put("eventId", OntologyRepository.str(row.get("event_id")));
        view.put("origin", OntologyRepository.str(row.get("origin")));
        view.put("selfObject", Resp.map("globalId", globalId, "title", titleOf(globalId)));
        view.put("changeKind", OntologyRepository.str(row.get("change_kind")));
        view.put("diff", repository.parseList(row.get("diff_json")));
        view.put("relationsAdded", repository.parseList(row.get("relations_added")));
        view.put("relationsRemoved", repository.parseList(row.get("relations_removed")));
        view.put("impact", impacts);
        view.put("source", Resp.map(
                "eventId", OntologyRepository.str(row.get("event_id")),
                "mappingVersion", OntologyRepository.str(row.get("mapping_version"))));
        view.put("reason", OntologyRepository.str(row.get("reason")));
        view.put("operator", OntologyRepository.str(row.get("operator")));
        // 两类时间严格分离：businessTime 仅业务事件有值；processedAt 恒为真实处理时间。
        view.put("businessTime", Resp.ts(OntologyRepository.dateTime(row.get("business_time"))));
        view.put("processedAt", Resp.ts(OntologyRepository.dateTime(row.get("processed_at"))));
        view.put("mappingVersion", OntologyRepository.str(row.get("mapping_version")));
        if (detail) {
            view.put("detail", true);
        }
        return view;
    }

    private String titleOf(String globalId) {
        Map<String, Object> row = repository.objectRow(globalId);
        return row.isEmpty() ? globalId : OntologyRepository.str(row.get("title"));
    }
}

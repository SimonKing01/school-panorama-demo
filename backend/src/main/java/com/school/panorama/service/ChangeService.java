package com.school.panorama.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.mapper.ChangeRecordMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 变更记录读取与 ChangeItem 组装；JSONB 快照统一解析后复用给列表、详情与总览。 */
@Service
public class ChangeService {

    @Autowired private ChangeRecordMapper changeRecordMapper;
    @Autowired private DemoClockService demoClock;
    @Autowired private ObjectMapper objectMapper;

    public List<Map<String, Object>> recent(int limit) {
        return toItems(changeRecordMapper.listChanges(null, limit));
    }

    public List<Map<String, Object>> list(Long schoolId, int limit) {
        return toItems(changeRecordMapper.listChanges(schoolId, limit));
    }

    public long count(Long schoolId) {
        return changeRecordMapper.countChanges(schoolId);
    }

    public Map<String, Object> detail(long id) {
        Map<String, Object> row = changeRecordMapper.getChange(id);
        if (row == null) {
            throw new ApiException(404, "NOT_FOUND", "变更记录不存在");
        }
        Map<String, Object> item = toItem(row);
        item.put("relations", relations(row));
        item.put("demoTime", demoClock.demoTimeString());
        return item;
    }

    private List<Map<String, Object>> toItems(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            out.add(toItem(row));
        }
        return out;
    }

    public Map<String, Object> toItem(Map<String, Object> row) {
        Map<String, Object> before = asMap(parseJson(row.get("before_json")));
        Map<String, Object> after = asMap(parseJson(row.get("after_json")));
        Object diff = parseJson(row.get("diff_json"));
        Object impact = parseJson(row.get("affected_json"));
        Object className = after.get("className") != null ? after.get("className") : before.get("className");
        Object courseName = after.get("courseName") != null ? after.get("courseName") : before.get("courseName");
        return Resp.map(
                "id", row.get("id"),
                "sessionId", row.get("session_id"),
                "sessionCode", row.get("session_code"),
                "schoolId", row.get("school_id"),
                "changeType", row.get("change_type"),
                "reason", row.get("reason"),
                "operator", row.get("operator"),
                "occurredAt", row.get("occurred_at"),
                "before", before,
                "after", after,
                "diff", diff,
                "impact", impact,
                "affectedStudentCount", row.get("affected_student_count"),
                "className", className,
                "courseName", courseName);
    }

    /** 详情里的可直接跳转对象链接信息。 */
    private List<Map<String, Object>> relations(Map<String, Object> row) {
        List<Map<String, Object>> rel = new ArrayList<>();
        Map<String, Object> impact = asMap(parseJson(row.get("affected_json")));
        addRel(rel, "class", impact.get("classIds"), impact.get("classNames"), "涉及班级");
        addRel(rel, "teacher", impact.get("teacherIds"), impact.get("teacherNames"), "涉及教师");
        addRel(rel, "classroom", impact.get("classroomIds"), impact.get("classroomNames"), "涉及教室");
        rel.add(Resp.map("type", "session", "id", row.get("session_id"),
                "code", row.get("session_code"), "name", row.get("session_code"), "role", "关联授课安排"));
        return rel;
    }

    private void addRel(List<Map<String, Object>> rel, String type, Object ids, Object names, String role) {
        if (!(ids instanceof List)) {
            return;
        }
        List<?> idList = (List<?>) ids;
        List<?> nameList = names instanceof List ? (List<?>) names : List.of();
        for (int i = 0; i < idList.size(); i++) {
            Object name = i < nameList.size() ? nameList.get(i) : "";
            rel.add(Resp.map("type", type, "id", idList.get(i), "code", "", "name", name, "role", role));
        }
    }

    private Object parseJson(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return objectMapper.readValue(v.toString(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }
}

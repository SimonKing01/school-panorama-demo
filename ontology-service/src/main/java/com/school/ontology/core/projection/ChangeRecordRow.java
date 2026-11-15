package com.school.ontology.core.projection;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一条变更历史。origin=EVENT 时 operator/businessTime 来自事件；
 * origin=RECONCILE/REBUILD 时二者必须为 null —— 不伪造操作人、发生时间与中间过程（D7）。
 */
public record ChangeRecordRow(String eventId, String globalId, String changeKind, String origin,
                              Map<String, Object> before, Map<String, Object> after,
                              List<Map<String, Object>> diff,
                              List<Map<String, Object>> relationsAdded,
                              List<Map<String, Object>> relationsRemoved,
                              String reason, String operator, LocalDateTime businessTime,
                              String mappingVersion) {
}

package com.school.panorama.service;

import com.school.panorama.common.ApiException;
import com.school.panorama.common.Resp;
import com.school.panorama.dto.AdjustBody;
import com.school.panorama.dto.LessonView;
import com.school.panorama.mapper.LessonSessionMapper;
import com.school.panorama.mapper.SystemRevisionMapper;
import com.school.panorama.outbox.OutboxEventWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 【本轮新增，非原有接口】批量调课（03 契约 §1.3、02 文档 §6.1）。
 *
 * <p>单一本地事务：事务开始取 advisory lock 串行化 → 逐条复用 AdjustService 的
 * 同一套 8 条校验（校验时能看到本事务内已 apply 的改动，避免批次内部互相冲突）
 * → 任一冲突整体抛 ApiException(400) 回滚，零写入、零事件
 * → 全通过才逐条写 change_records → bumpRevision() 一次
 * → 写 1 条 BATCH_ADJUST 批次事件（payload.items[] 共用 batch_id）→ 提交。</p>
 *
 * <p>注释与契约均标注「本轮新增，非原有」；docs/API-CONTRACT.md 既有端点逐字不改，
 * preview 仍不产生事件。</p>
 */
@Service
public class BatchAdjustService {

    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_ITEMS = 50;

    @Autowired private AdjustService adjustService;
    @Autowired private LessonSessionMapper lessonSessionMapper;
    @Autowired private SystemRevisionMapper systemRevisionMapper;
    @Autowired private BusinessQueryService business;
    @Autowired private ChangeService changeService;
    @Autowired private DemoClockService demoClock;
    @Autowired private OutboxEventWriter outboxEventWriter;

    @Transactional
    public Map<String, Object> batchAdjust(Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String fallbackReason = asString(request.get("reason"));
        List<Map<String, Object>> items = parseItems(request.get("items"));
        if (items.isEmpty()) {
            throw badRequest("批量调课 items 不能为空", List.of(), List.of());
        }
        if (items.size() > MAX_ITEMS) {
            throw badRequest("批量调课最多 " + MAX_ITEMS + " 条", List.of(), List.of());
        }

        // 事务开始即串行化，与单条 adjust 共用同一把 advisory lock。
        lessonSessionMapper.advisoryLock();
        String batchId = "BATCH-" + LocalDateTime.now().format(BATCH_TIME)
                + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();

        List<AdjustService.Eval> evals = new ArrayList<>();
        List<Long> sessionIds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<Map<String, Object>> itemPreviews = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            Long sessionId = asLong(item.get("sessionId"));
            if (sessionId == null) {
                conflicts.add(Resp.map("type", "SESSION_ID_REQUIRED",
                        "message", "第 " + (i + 1) + " 条：sessionId 必填"));
                itemPreviews.add(Map.of());
                evals.add(null);
                sessionIds.add(-1L);
                reasons.add("");
                continue;
            }
            String reason = notBlank(asString(item.get("reason")))
                    ? asString(item.get("reason")) : fallbackReason;
            AdjustBody adjustBody = toAdjustBody(item, reason);
            try {
                AdjustService.Eval eval = adjustService.validate(sessionId, adjustBody);
                itemPreviews.add(eval.view);
                evals.add(eval);
                sessionIds.add(sessionId);
                reasons.add(reason);
                for (Object conflict : eval.conflicts) {
                    if (conflict instanceof Map<?, ?> map) {
                        conflicts.add(Resp.map("type", map.get("type"),
                                "message", "第 " + (i + 1) + " 条：" + String.valueOf(map.get("message"))));
                    }
                }
                // 立刻 apply，让后续条目的校验能看到本事务内已生效的课表改动。
                if (eval.conflicts.isEmpty()) {
                    adjustService.applyValidated(sessionId, eval);
                }
            } catch (ApiException e) {
                conflicts.add(Resp.map("type", e.code,
                        "message", "第 " + (i + 1) + " 条：" + e.getMessage()));
                itemPreviews.add(Map.of());
                evals.add(null);
                sessionIds.add(sessionId);
                reasons.add(reason);
            }
        }

        if (!conflicts.isEmpty()) {
            // 抛异常整体回滚：课表、变更记录、revision、Outbox 事件均零写入。
            throw badRequest("批量调课校验未通过", conflicts, itemPreviews);
        }

        List<Map<String, Object>> changes = new ArrayList<>();
        List<Map<String, Object>> payloadItems = new ArrayList<>();
        List<Map<String, Object>> allDiffs = new ArrayList<>();
        Map<String, Set<Object>> impactAccumulator = new LinkedHashMap<>();
        for (int i = 0; i < evals.size(); i++) {
            AdjustService.Eval eval = evals.get(i);
            long sessionId = sessionIds.get(i);
            String reason = reasons.get(i);
            long changeId = adjustService.insertChangeRecord(sessionId, reason, eval);
            changes.add(changeService.detail(changeId));
            payloadItems.add(Resp.map(
                    "sourceTable", "lesson_sessions", "sourcePk", String.valueOf(sessionId),
                    "sourceCode", eval.lesson.code,
                    "before", eval.before, "after", eval.after,
                    "diff", eval.diff, "impact", eval.impact, "reason", reason));
            for (Map<String, Object> diff : eval.diff) {
                allDiffs.add(diff);
            }
            mergeImpact(impactAccumulator, eval.impact);
        }

        // 整体只 bump 一次，事件 revision 与事务一致。
        systemRevisionMapper.bump();
        long newRevision = systemRevisionMapper.revision();
        Map<String, Object> mergedImpact = snapshot(impactAccumulator);
        Map<String, Object> payload = Resp.map(
                "batchId", batchId,
                "kind", "BATCH_ADJUST",
                "items", payloadItems,
                "diff", allDiffs,
                "impact", mergedImpact,
                "reason", fallbackReason,
                "operator", "演示管理员");
        outboxEventWriter.append("BATCH_ADJUST", "BATCH", batchId, batchId,
                newRevision, demoClock.now(), payload);

        List<LessonView> lessons = new ArrayList<>();
        List<LessonView> all = business.allLessons();
        for (Long sessionId : sessionIds) {
            all.stream().filter(l -> l.id == sessionId).findFirst().ifPresent(lessons::add);
        }
        return Resp.map(
                "demoTime", demoClock.demoTimeString(),
                "revision", newRevision,
                "batchId", batchId,
                "appliedCount", evals.size(),
                "lessons", lessons,
                "changes", changes,
                "impact", mergedImpact);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseItems(Object raw) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static AdjustBody toAdjustBody(Map<String, Object> item, String reason) {
        AdjustBody body = new AdjustBody();
        body.teacherId = asLong(item.get("teacherId"));
        body.classroomId = asLong(item.get("classroomId"));
        body.lessonDate = asString(item.get("lessonDate"));
        body.startTime = asString(item.get("startTime"));
        body.endTime = asString(item.get("endTime"));
        body.reason = reason;
        return body;
    }

    private static ApiException badRequest(String message, List<Map<String, Object>> conflicts,
                                           List<Map<String, Object>> items) {
        Map<String, Object> fail = new LinkedHashMap<>();
        // demoTime 由 GlobalExceptionHandler 统一补为演示业务时间，不用真实处理时间冒充。
        fail.put("valid", false);
        fail.put("conflicts", conflicts);
        fail.put("items", items);
        fail.put("message", message);
        fail.put("code", "VALIDATION_FAILED");
        return new ApiException(400, "VALIDATION_FAILED", message, conflicts, fail);
    }

    @SuppressWarnings("unchecked")
    private static void mergeImpact(Map<String, Set<Object>> accumulator, Map<String, Object> impact) {
        for (Map.Entry<String, Object> entry : impact.entrySet()) {
            if (!(entry.getValue() instanceof List<?> values)) {
                continue;
            }
            Set<Object> target = accumulator.computeIfAbsent(entry.getKey(),
                    ignored -> new LinkedHashSet<>());
            for (Object value : values) {
                target.add(value);
            }
        }
    }

    private static Map<String, Object> snapshot(Map<String, Set<Object>> accumulator) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Object>> entry : accumulator.entrySet()) {
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return result;
    }

    private static Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}

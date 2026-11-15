package com.school.ontology.controller;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.OntApiResponse;
import com.school.ontology.common.Resp;
import com.school.ontology.core.sync.EventConsumer;
import com.school.ontology.core.sync.ReconcileService;
import com.school.ontology.core.sync.RebuildService;
import com.school.ontology.core.sync.SyncTaskService;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.repository.SourceOutboxRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** 同步任务 / 全量导入 / 对账 / 重建 / 失败重试（E5、US-07、验收三）。 */
@RestController
@RequestMapping("/api/ontology")
public class SyncAdminController {

    private final SyncTaskService syncTaskService;
    private final ReconcileService reconcileService;
    private final RebuildService rebuildService;
    private final SourceOutboxRepository outbox;
    private final EventConsumer consumer;
    private final OntologyRepository repository;
    /** 业务服务内部地址：仅用于裁决 4 的失败重试代理转发，默认走 Compose 服务名。 */
    private final String businessApiBase;
    private final HttpClient adminHttpClient;

    public SyncAdminController(SyncTaskService syncTaskService, ReconcileService reconcileService,
                               RebuildService rebuildService, SourceOutboxRepository outbox,
                               EventConsumer consumer, OntologyRepository repository,
                               @Value("${ontology.business-api-base:http://backend:8080}")
                               String businessApiBase) {
        this.syncTaskService = syncTaskService;
        this.reconcileService = reconcileService;
        this.rebuildService = rebuildService;
        this.outbox = outbox;
        this.consumer = consumer;
        this.repository = repository;
        this.businessApiBase = businessApiBase;
        this.adminHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @GetMapping("/sync/tasks")
    public OntApiResponse<Map<String, Object>> tasks(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OntApiResponse.ok(syncTaskService.tasks(type, page, size));
    }

    @PostMapping("/import/full")
    public OntApiResponse<Map<String, Object>> fullImport(
            @RequestBody(required = false) Map<String, Object> body) {
        rejectRunningTask();
        long taskId = syncTaskService.fullImport();
        Map<String, Object> task = repository.syncTask(taskId);
        return OntApiResponse.ok(Resp.map(
                "taskId", taskId,
                "type", "FULL_IMPORT",
                "status", OntologyRepository.str(task.get("status")),
                "snapshotRevision", OntologyRepository.lng(task.get("snapshot_revision")),
                "message", "全量导入已启动"));
    }

    @PostMapping("/import/retry")
    public OntApiResponse<Map<String, Object>> retryImport(@RequestBody Map<String, Object> body) {
        long failedTaskId = Long.parseLong(String.valueOf(body.getOrDefault("taskId", "-1")));
        rejectRunningTask();
        long taskId = syncTaskService.retry(failedTaskId);
        return OntApiResponse.ok(Resp.map("taskId", taskId, "type", "FULL_IMPORT",
                "status", "RUNNING", "retryFromTaskId", failedTaskId,
                "message", "已从水位幂等重跑"));
    }

    @GetMapping("/import/progress")
    public OntApiResponse<Map<String, Object>> progress(@RequestParam long taskId) {
        return OntApiResponse.ok(syncTaskService.progress(taskId));
    }

    @PostMapping("/reconcile")
    public OntApiResponse<Map<String, Object>> reconcile(
            @RequestBody(required = false) Map<String, Object> body) {
        boolean dryRun = body != null && Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("dryRun", "false")));
        long runId = reconcileService.reconcile(dryRun);
        return OntApiResponse.ok(Resp.map("runId", runId,
                "status", dryRun ? "DRY_RUN" : "SUCCESS",
                "message", dryRun ? "仅列出差异，未修复当前投影" : "对账完成，仅修复当前投影，未伪造历史"));
    }

    @GetMapping("/reconcile/runs")
    public OntApiResponse<Map<String, Object>> reconcileRuns(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OntApiResponse.ok(reconcileService.runs(page, size));
    }

    @PostMapping("/rebuild")
    public OntApiResponse<Map<String, Object>> rebuild(
            @RequestBody(required = false) Map<String, Object> body) {
        long taskId = rebuildService.rebuild();
        return OntApiResponse.ok(Resp.map(
                "taskId", taskId, "type", "REBUILD", "status", "RUNNING",
                "scope", "CURRENT_PROJECTION_ONLY",
                "truncatedTables", java.util.List.of(
                        "ont_object", "ont_relation", "ont_source_ref", "ont_object_version"),
                "preservedTables", java.util.List.of(
                        "ont_change_history", "ont_change_impact", "ont_sync_task",
                        "ont_processed_event", "ont_error_log", "ont_reconcile_run",
                        "ont_model_def", "ont_mapping_version", "ont_data_source",
                        "ont_platform_revision"),
                "message", "仅重建当前投影，审计历史全保留"));
    }

    /** DEAD/FAILED 事件查看（只读业务库 Outbox）。 */
    @GetMapping("/admin/outbox")
    public OntApiResponse<Map<String, Object>> adminOutbox(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return OntApiResponse.ok(Resp.map("total", outbox.list(status, size,
                        Math.max(page - 1, 0) * size).size(),
                "items", outbox.list(status, size, Math.max(page - 1, 0) * size)));
    }

    /**
     * 失败重试（主理人裁决 4）：底座对业务库只有只读权限，绝不回写 outbox_events。
     * 因此本端点<b>代理转发</b>到业务服务 {@code POST /admin/retry?eventId=...}，
     * 由业务侧把 DEAD/FAILED 事件重新排队（status=PENDING、attempts=0），
     * 再由业务投递器按原有 revision 重投；底座返回 03 契约 §2.13 约定的
     * {@code status:"PENDING", attempts:0, message:"已重新排队"}。
     * 业务服务不可用时返回明确的中文错误，不静默假装成功。
     */
    @PostMapping("/admin/retry")
    public OntApiResponse<Map<String, Object>> adminRetry(@RequestParam String eventId) {
        String url = businessApiBase.replaceAll("/+$", "") + "/admin/retry?eventId="
                + URLEncoder.encode(eventId, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = adminHttpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return OntApiResponse.ok(Resp.map(
                        "eventId", eventId, "status", "PENDING", "attempts", 0,
                        "message", "已重新排队（业务侧已回写 Outbox 状态，底座只读）"));
            }
            return OntApiResponse.fail("SOURCE_UNAVAILABLE",
                    "业务服务不可用，无法重放事件（HTTP " + response.statusCode() + "）");
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return OntApiResponse.fail("SOURCE_UNAVAILABLE",
                    "业务服务不可用，无法重放事件：" + reason);
        }
    }

    private void rejectRunningTask() {
        if (repository.hasRunningTask("FULL_IMPORT") || repository.hasRunningTask("REBUILD")) {
            throw ApiException.conflict("已有导入/重建任务在运行");
        }
    }
}

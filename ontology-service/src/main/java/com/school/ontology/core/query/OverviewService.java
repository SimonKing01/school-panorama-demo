package com.school.ontology.core.query;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.spi.BusinessTime;
import com.school.ontology.core.spi.SourceAdapter;
import com.school.ontology.repository.OntologyRepository;
import com.school.ontology.repository.SourceOutboxRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全景总览与数据源新鲜度（E1/E6/D8）。
 *
 * <p>状态五态：SYNCED / NO_DATA / NOT_CONNECTED / LAGGING / FAILED；
 * sourceUp=false 是叠加标记而非第五态。两类时间分别读取：demoTime 来自
 * 演示业务时间投影，lastSuccessSyncAt 来自真实同步处理时间。</p>
 */
@Component
public class OverviewService {

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "SYNCED", "已同步（新鲜）",
            "NO_DATA", "没有业务数据",
            "NOT_CONNECTED", "尚未接入",
            "LAGGING", "同步落后",
            "FAILED", "同步失败");

    private static final Map<String, String> STATUS_HINTS = Map.of(
            "SYNCED", "",
            "NO_DATA", "来源中暂无业务数据，请检查原业务演示数据；这与「尚未接入」不同。",
            "NOT_CONNECTED", "尚未成功导入，请在「模型与接入管理」执行一次全量导入。",
            "LAGGING", "存在待处理事件，底座正在追赶；恢复后将自动同步。",
            "FAILED", "存在失败或未恢复的事件，请查看错误并在管理页执行失败重试。");

    private final OntologyRepository repository;
    private final SourceOutboxRepository outbox;
    private final SourceAdapter adapter;
    private final ModelRegistry registry;
    private final ChangeQueryService changeQuery;

    public OverviewService(OntologyRepository repository, SourceOutboxRepository outbox,
                           SourceAdapter adapter, ModelRegistry registry,
                           ChangeQueryService changeQuery) {
        this.repository = repository;
        this.outbox = outbox;
        this.adapter = adapter;
        this.registry = registry;
        this.changeQuery = changeQuery;
    }

    public Map<String, Object> overview() {
        Map<String, Object> status = sourceStatus();
        Map<String, Object> counts = Resp.map(
                "objects", repository.countObjects(),
                "relations", repository.countRelations(),
                "objectTypes", registry.types().size(),
                "relationTypes", registry.relations().size());
        Map<String, Object> dataSource = Resp.map(
                "sourceUp", status.get("sourceUp"),
                "status", status.get("status"),
                "lastSuccessSyncAt", status.get("lastSuccessSyncAt"),
                "pendingCount", status.get("pendingCount"));
        Map<String, Object> sync = Resp.map(
                "revision", repository.platformRevision(),
                "demoTime", status.get("demoTime"),
                "errorCount", status.get("errorCount"),
                "freshness", status.get("freshness"));
        Map<String, Object> recent = changeQuery.changes(null, null, 1, 5);
        Map<String, Object> impactSummary = Resp.map(
                "selfChanged", repository.countImpactByRole("SELF_CHANGED"),
                "derivedRelated", repository.countImpactByRole("DERIVED_RELATED"));
        return Resp.map("counts", counts, "dataSource", dataSource, "sync", sync,
                "recentChanges", recent.get("items"), "impactSummary", impactSummary);
    }

    public Map<String, Object> sourceStatus() {
        String sourceId = registry.sourceId();
        Map<String, Object> ds = repository.dataSource(sourceId);
        boolean sourceUp;
        try {
            sourceUp = adapter.healthCheck();
        } catch (Exception e) {
            sourceUp = false;
        }
        Map<String, Long> counts = outbox.statusCounts();
        long pendingCount = counts.getOrDefault("pendingCount", 0L);
        long deadCount = counts.getOrDefault("deadCount", 0L);
        long failedCount = counts.getOrDefault("failedCount", 0L);
        long errorCount = OntologyRepository.lng(ds.get("error_count"));
        String lastSuccessSyncAt = Resp.ts(
                OntologyRepository.dateTime(ds.get("last_success_sync_at")));
        long snapshotRevision = OntologyRepository.lng(ds.get("snapshot_revision"));
        long currentSourceRevision = sourceUp
                ? adapter.currentWatermark()
                : OntologyRepository.lng(ds.get("current_source_revision"));
        long objectCount = repository.countObjects();
        String status = resolveStatus(sourceUp, pendingCount, deadCount, failedCount, errorCount,
                snapshotRevision, currentSourceRevision, lastSuccessSyncAt, objectCount);
        // 重建失败会把投影留在「已清空、只写回一部分」的残缺状态（P7-2）。此时各计数指标可能
        // 全都正常（pending=0、dead=0、error=0、水位也对得上），只看指标会对外宣称「已同步」，
        // 而实际对象少了一大半。因此数据源上的 DEGRADED 标记必须优先于指标重算结果。
        if ("FAILED".equals(status) == false
                && "DEGRADED".equals(OntologyRepository.str(ds.get("status")))) {
            status = "FAILED";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", sourceId);
        result.put("sourceUp", sourceUp);
        result.put("status", status);
        result.put("statusLabel", STATUS_LABELS.get(status));
        result.put("lastSuccessSyncAt", lastSuccessSyncAt);
        result.put("demoTime", Resp.ts(currentDemoTime(ds)));
        result.put("snapshotRevision", snapshotRevision);
        result.put("currentSourceRevision", currentSourceRevision);
        result.put("pendingCount", pendingCount);
        result.put("processingCount", 0L);
        result.put("errorCount", errorCount);
        result.put("deadCount", deadCount);
        result.put("freshness", STATUS_LABELS.get(status));
        result.put("mappingVersion", OntologyRepository.str(ds.get("mapping_version")));
        result.put("hint", hintOf(status, sourceUp, snapshotRevision, currentSourceRevision));
        return result;
    }

    public Map<String, Object> revision() {
        Map<String, Object> status = sourceStatus();
        return Resp.map("revision", repository.platformRevision(),
                "demoTime", status.get("demoTime"),
                "lastSuccessSyncAt", status.get("lastSuccessSyncAt"),
                "pendingCount", status.get("pendingCount"),
                "sourceUp", status.get("sourceUp"));
    }

    /** 前端短轮询与动态计算共用的演示业务时间来源。 */
    public LocalDateTime currentDemoTime(Map<String, Object> ds) {
        LocalDateTime demoAt = OntologyRepository.dateTime(ds.get("demo_time"));
        boolean running = OntologyRepository.bool(ds.get("demo_running"));
        LocalDateTime anchor = OntologyRepository.dateTime(ds.get("anchor_real_at"));
        if (demoAt == null) {
            return LocalDateTime.now();
        }
        return new BusinessTime(demoAt, running, anchor).at(LocalDateTime.now());
    }

    /**
     * 五态判定（顺序即优先级）。与具体业务类型无关，只看同步健康度数据。
     *
     * <p>{@code lastSuccessSyncAt == null} 判定排在「水位落后」之前：
     * 从未成功导入过的库 snapshot_revision=0、来源 revision 必然更大，
     * 若先判落后会永远显示「同步落后」，把「尚未接入」这个更可操作的状态掩盖掉。</p>
     */
    static String resolveStatus(boolean sourceUp, long pendingCount, long deadCount,
                                long failedCount, long errorCount, long snapshotRevision,
                                long currentSourceRevision, String lastSuccessSyncAt,
                                long objectCount) {
        if (deadCount > 0 || failedCount > 0 || errorCount > 0) {
            return "FAILED";
        }
        if (!sourceUp) {
            return "LAGGING";
        }
        if (pendingCount > 0) {
            return "LAGGING";
        }
        if (lastSuccessSyncAt == null) {
            return "NOT_CONNECTED";
        }
        if (snapshotRevision < currentSourceRevision) {
            return "LAGGING";
        }
        if (objectCount == 0) {
            return "NO_DATA";
        }
        return "SYNCED";
    }

    /** 文案不写死：落后时带上两个真实水位数字，便于直接看出差多少。 */
    static String hintOf(String status, boolean sourceUp, long snapshotRevision,
                         long currentSourceRevision) {
        if (!sourceUp) {
            return "来源不可用：仍可查询已同步数据；最后成功同步时间见上。";
        }
        if ("LAGGING".equals(status) && snapshotRevision < currentSourceRevision) {
            return "同步落后：快照水位 " + snapshotRevision + " < 来源 " + currentSourceRevision
                    + "，请在全量导入或重建后重试。";
        }
        return STATUS_HINTS.get(status);
    }
}

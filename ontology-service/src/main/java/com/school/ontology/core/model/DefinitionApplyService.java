package com.school.ontology.core.model;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.repository.OntologyRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「应用到运行时」（07 增量设计 §2.2 / §6.6 / §12-S7）。
 *
 * <p>路线 A + 内建降级 B：</p>
 * <ul>
 *   <li>自重启开启（仅容器编排）→ 返回 202 并在约 800ms 后 {@code System.exit(0)}，
 *       由 compose 的 {@code restart: unless-stopped} 拉起；延迟是为了让 HTTP 响应先落回前端。</li>
 *   <li>自重启关闭（裸 {@code java -jar} 默认）→ 返回 200 {@code pendingRestart}，
 *       提示手动重启；绝不自杀，避免永久宕机。</li>
 * </ul>
 *
 * <p>防重启风暴：草稿版本 == 生效版本 → 200 {@code noChange}，不重启；
 * 并发 apply 用内存标记拦成 409 {@code APPLY_IN_PROGRESS}。</p>
 */
@Component
public class DefinitionApplyService {

    /** 延迟退出时长：先让 202 响应刷出前端，再退出进程。 */
    private static final long RESTART_DELAY_MS = 800L;
    private static final Logger log = LoggerFactory.getLogger(DefinitionApplyService.class);

    private final ModelDefinitionLoader loader;
    private final DefinitionFileStore fileStore;
    private final OntologyRepository repository;
    private final boolean selfRestartEnabled;
    private final AtomicBoolean applying = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ontology-definition-restart");
        thread.setDaemon(true);
        return thread;
    });
    /** 退出动作；单元测试替换为「只记录不退出」，保证测试进程永远不会被 {@code System.exit} 杀掉。 */
    private volatile Runnable restartAction = () -> System.exit(0);

    @Autowired
    public DefinitionApplyService(ModelDefinitionLoader loader, DefinitionFileStore fileStore,
                                  OntologyRepository repository,
                                  @Value("${ontology.self-restart-enabled:false}")
                                  boolean selfRestartEnabled) {
        this(loader, fileStore, repository, selfRestartEnabled, () -> System.exit(0));
    }

    /** 测试用构造：注入可控的退出动作。 */
    DefinitionApplyService(ModelDefinitionLoader loader, DefinitionFileStore fileStore,
                           OntologyRepository repository, boolean selfRestartEnabled,
                           Runnable restartAction) {
        this.loader = loader;
        this.fileStore = fileStore;
        this.repository = repository;
        this.selfRestartEnabled = selfRestartEnabled;
        this.restartAction = restartAction == null ? () -> System.exit(0) : restartAction;
    }

    /** 是否已有一次应用在途（供管理页展示 / 单测断言）。 */
    public boolean applying() {
        return applying.get();
    }

    /**
     * 应用草稿：乐观版本校验 → noChange 判断 → 202（自重启）或 200（待手动重启）。
     */
    public Map<String, Object> apply(RelationEditRequest request) {
        String sourceId = loader.registry().sourceId();
        if (request == null) {
            throw ApiException.badRequest("请求体不能为空");
        }
        if (request.baseMappingVersion == null || request.baseMappingVersion.isBlank()) {
            throw ApiException.badRequest("baseMappingVersion 不能为空：请刷新页面后重试");
        }
        guardTaskConflict();
        if (!applying.compareAndSet(false, true)) {
            throw new ApiException(409, "APPLY_IN_PROGRESS",
                    "已有一次『应用到运行时』正在进行，请稍候");
        }
        try {
            String active = activeVersion(sourceId);
            if (!active.equals(request.baseMappingVersion)) {
                throw new ApiException(409, "VERSION_CONFLICT",
                        "生效版本已变化（当前 " + active + "，请求基于 " + request.baseMappingVersion
                                + "），请刷新页面后重试");
            }
            String target = targetVersion(sourceId);
            if (target == null) {
                throw new ApiException(400, "NO_DRAFT",
                        "当前没有待应用的草稿：请先保存对关系类型的修改");
            }
            // ── 幂等①（P7-3）：目标 == 本进程已加载的定义 → 重启不可能改变任何东西 ──
            // 必须排在「与 DB 里的 ACTIVE 比对」之前。重启后存在约 100ms 的窗口：Tomcat 已就绪，
            // 而 DefinitionActivationRunner 还没把 ACTIVE 行写成新版本。此时若只比 DB，
            // 会误判成「版本不同」→ 再排一次 800ms 退出 → 重启风暴把启动自愈的重建打断在半路
            // （实测现场：task 28 永久 RUNNING、投影只剩 38/95 个对象）。
            String loaded = loader.mappingVersion().mappingVersion;
            if (target.equals(loaded)) {
                applying.set(false);
                return Resp.map("applying", false, "noChange", true,
                        "targetMappingVersion", target,
                        "message", "目标定义已是当前运行的定义，无需重启");
            }
            if (target.equals(active)) {
                applying.set(false);
                return Resp.map("applying", false, "noChange", true,
                        "targetMappingVersion", target,
                        "message", "草稿与当前生效版本一致，无需应用");
            }
            if (!selfRestartEnabled) {
                applying.set(false);
                return Resp.map("applying", false, "pendingRestart", true,
                        "targetMappingVersion", target,
                        "message", "已校验通过；当前未启用自动重启，请手动重启本体服务以生效");
            }
            scheduleRestart(target);
            return Resp.map("applying", true, "restartScheduled", true,
                    "targetMappingVersion", target,
                    "message", "正在应用，本体服务将在约 1 秒后重启（5–10 秒恢复）");
        } catch (RuntimeException e) {
            applying.set(false);
            throw e;
        }
    }

    private void scheduleRestart(String targetVersion) {
        log.warn("在线定义已应用，{}ms 后重启本体服务以加载新定义：{}", RESTART_DELAY_MS, targetVersion);
        try {
            scheduler.schedule(() -> {
                try {
                    restartAction.run();
                } catch (Throwable error) {
                    // 退出动作本身失败（如被安全管理器拦截）：解除标记，允许用户重试或手动重启。
                    log.error("重启本体服务失败，请手动重启以加载新定义：{}", error.getMessage());
                    applying.set(false);
                }
            }, RESTART_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            applying.set(false);
            throw new IllegalStateException("安排重启任务失败，请手动重启本体服务以加载新定义", e);
        }
    }

    private String activeVersion(String sourceId) {
        try {
            String fromDb = OntologyRepository.str(
                    repository.activeMappingVersion(sourceId).get("mapping_version"));
            if (fromDb != null && !fromDb.isBlank()) {
                return fromDb;
            }
        } catch (Exception e) {
            log.warn("读取当前生效版本失败，回退内存版本：{}", e.getMessage());
        }
        return loader.mappingVersion().mappingVersion;
    }

    /**
     * 「重启后会生效」的目标版本。
     *
     * <ul>
     *   <li>存在 overlay → 用 overlay 文本算出的版本（真正的在线草稿）；</li>
     *   <li>不存在 overlay → 打包基线版本。这条分支是「恢复基线」之后的应用路径：
     *       草稿不是一个文件，而是「删掉 overlay 回到基线」，目标版本因此是基线版本。</li>
     * </ul>
     */
    private String targetVersion(String sourceId) {
        if (!fileStore.overlayExists()) {
            String baseline = loader.baselineMappingVersion().mappingVersion;
            return baseline == null || baseline.isBlank() ? null : baseline;
        }
        try {
            String overlayText = fileStore.readOverlayText();
            String hash = Resp.sha256(loader.objectText() + "\n" + overlayText
                    + "\n" + loader.sourceText());
            return sourceId + ":" + hash.substring(0, 12);
        } catch (Exception e) {
            log.warn("读取在线定义以计算草稿版本失败：{}", e.getMessage());
            return null;
        }
    }

    private void guardTaskConflict() {
        boolean running = false;
        try {
            running = repository.hasRunningTask("FULL_IMPORT") || repository.hasRunningTask("REBUILD");
        } catch (Exception e) {
            log.warn("检查导入/重建任务状态失败，按无任务处理：{}", e.getMessage());
        }
        if (running) {
            throw new ApiException(409, "TASK_CONFLICT",
                    "当前有全量导入或重建任务正在运行，请等任务结束后再应用定义");
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}

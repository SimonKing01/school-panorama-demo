package com.school.ontology.core.model;

import com.school.ontology.core.sync.RebuildService;
import com.school.ontology.repository.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 启动自愈（07 增量设计 §8 / §12-S6）。
 *
 * <p><b>为什么必须是 {@code @Order(15)}</b>：{@code MappingVersionRecorder} 是
 * {@code @Order(20)}，每次启动都会把当前版本 upsert 成 ACTIVE；自愈检测若晚于它执行，
 * 就永远读不到「上一轮真正生效的版本」，哈希比对必然一致 → 重建永不触发。
 * 因此顺序固定为 10（建表/加列）→ 15（本类）→ 20（既有登记，幂等）。</p>
 *
 * <p><b>为什么重建放 {@link ApplicationReadyEvent}</b>：此时 Tomcat 已就绪，
 * 重建在后台跑，不阻塞端口可用；重建失败由 {@link RebuildService} 内部记
 * {@code ont_sync_task=FAILED} + {@code ont_error_log}，服务继续以「新定义 + 旧投影」可用。</p>
 *
 * <p><b>为什么第一件事是清孤儿任务（P7-1/P7-2）</b>：{@code RebuildService} 先写
 * {@code ont_sync_task=RUNNING} 再干活，进程被重启打断时 {@code catch} 来不及执行，
 * 该行会永久停在 RUNNING，把 apply / 关系编辑 / 管理端导入三条写路径永久挡住。
 * 单实例部署下「启动时库里的 RUNNING 必然是上一轮的孤儿」，因此这里直接清成
 * {@code INTERRUPTED}；清理条数 &gt;0 同时作为「上一轮没跑完 ⇒ 投影可能残缺」的信号，
 * 即使定义哈希没变也强制重建，避免残缺投影被永久保留且对外仍报 SYNCED。</p>
 */
@Component
@Order(15)
public class DefinitionActivationRunner implements ApplicationRunner {

    /** 与设计文档一致的顺序常量，便于其它组件引用与测试断言。 */
    public static final int ORDER = 15;
    private static final Logger log = LoggerFactory.getLogger(DefinitionActivationRunner.class);

    private final ModelDefinitionLoader loader;
    private final OntologyRepository repository;
    private final DefinitionVersionAttribution attribution;
    private final Runnable rebuildTrigger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ontology-definition-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    /** 本次启动是否需要重建当前投影（定义哈希与上一轮 ACTIVE 不一致时为 true）。 */
    private volatile boolean needRebuild;

    @Autowired
    public DefinitionActivationRunner(ModelDefinitionLoader loader, OntologyRepository repository,
                                      RebuildService rebuildService) {
        this(loader, repository, rebuildService::rebuild, new DefinitionVersionAttribution(repository));
    }

    /** 测试用构造：注入可控的重建动作。 */
    DefinitionActivationRunner(ModelDefinitionLoader loader, OntologyRepository repository,
                               Runnable rebuildTrigger) {
        this(loader, repository, rebuildTrigger, new DefinitionVersionAttribution(repository));
    }

    DefinitionActivationRunner(ModelDefinitionLoader loader, OntologyRepository repository,
                               Runnable rebuildTrigger, DefinitionVersionAttribution attribution) {
        this.loader = loader;
        this.repository = repository;
        this.rebuildTrigger = rebuildTrigger;
        this.attribution = attribution == null
                ? new DefinitionVersionAttribution(repository) : attribution;
    }

    public boolean needRebuild() {
        return needRebuild;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String sourceId = loader.registry().sourceId();
            String currentVersion = loader.mappingVersion().mappingVersion;
            String currentHash = "sha256:" + loader.definitionHash();
            // ── P7-1 孤儿任务清理：必须在任何版本比对之前 ──────────────────────
            // 单实例部署下，本进程刚启动时库里任何 RUNNING 任务都是上一轮被 kill 掉的孤儿，
            // 不清就会永久挡住 apply / 关系类型编辑 / 管理端导入三条写路径。
            int interrupted = repository.markInterruptedTasks(
                    "进程重启导致上一轮任务中断，未能完成");
            boolean interruptedBefore = interrupted > 0;
            if (interruptedBefore) {
                log.warn("检测到 {} 个上一轮被中断的任务，已置为 INTERRUPTED", interrupted);
            }
            // 署名解析（P6）：这一版如果是人保存的草稿，operator/note 必须原样保留，
            // 只有「没有人工署名」时才记 system / startup-<source>。
            DefinitionVersionAttribution.Attribution credit =
                    attribution.resolve(sourceId, currentVersion, loader.definitionSource());
            Map<String, Object> active = repository.activeMappingVersion(sourceId);
            if (active.isEmpty()) {
                // 首次启动：只登记版本，不重建——投影由全量导入负责，本类不越权。
                // 例外：上一轮也被中断过（首次导入没跑完），投影必然残缺，必须重建（P7-2）。
                repository.upsertMappingVersion(sourceId, currentVersion, currentHash, "ACTIVE",
                        credit.operator(), credit.note(), credit.definitionSource());
                needRebuild = interruptedBefore;
                if (interruptedBefore) {
                    log.warn("首次启动登记生效定义版本：{}（{}），但上一轮任务被中断，将强制重建投影",
                            currentVersion, credit.definitionSource());
                } else {
                    log.info("首次启动登记生效定义版本：{}（{}，operator={}）",
                            currentVersion, credit.definitionSource(), credit.operator());
                }
                return;
            }
            String previousHash = OntologyRepository.str(active.get("definition_hash"));
            String previousVersion = OntologyRepository.str(active.get("mapping_version"));
            if (Objects.equals(currentHash, previousHash)) {
                // 普通重启：定义没变就不重建，避免「每次重启都重建一遍投影」。
                // 但如果上一轮有任务被中断（重建/导入跑了一半），投影可能残缺且对外仍显示
                // SYNCED（P7-2 现场：95 个对象只剩 38 个，overview 照样报 SYNCED）——
                // 此时必须强制重建，完整性信号优先于「省一次重建」。
                needRebuild = interruptedBefore;
                if (interruptedBefore) {
                    log.warn("生效定义未变化，但上一轮任务被中断，强制重建投影以确保完整性：{}",
                            currentVersion);
                } else {
                    log.info("生效定义未变化，跳过重建：{}", currentVersion);
                }
            } else {
                repository.supersedeOtherActive(sourceId, currentVersion);
                repository.upsertMappingVersion(sourceId, currentVersion, currentHash, "ACTIVE",
                        credit.operator(), credit.note(), credit.definitionSource());
                needRebuild = true;
                log.info("定义已变更：{}（{}）→ {}（{}），将重建当前投影（operator={}，note={}）",
                        previousVersion, previousHash, currentVersion, currentHash,
                        credit.operator(), credit.note());
            }
            // 生效版本已确定：与它不同的 PENDING 行都是过期草稿（例如写了 overlay 又在重启前
            // 删掉），置为 SUPERSEDED，避免版本面板出现「幽灵草稿」。与本版本相等的 PENDING
            // 行刚刚已被上行原地转成 ACTIVE，这里不会误伤。
            supersedeStalePending(sourceId, currentVersion);
        } catch (Exception e) {
            // 本体库可能尚未就绪：不阻断启动，下一轮重启/管理操作会重新比对。
            needRebuild = false;
            log.warn("启动自愈检测未能完成（本体库可能尚未就绪）：{}", e.getMessage());
        }
    }

    private void supersedeStalePending(String sourceId, String keepVersion) {
        try {
            repository.supersedeOtherPending(sourceId, keepVersion);
        } catch (Exception e) {
            log.warn("清理过期草稿版本失败（不影响本次启动）：{}", e.getMessage());
        }
    }

    /** 服务已可对外后再重建，失败只记日志（{@link RebuildService} 内部已留痕）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!needRebuild) {
            return;
        }
        needRebuild = false;
        try {
            executor.submit(() -> {
                try {
                    rebuildTrigger.run();
                    log.info("投影重建已完成（定义变更或上一轮任务中断）");
                } catch (Throwable error) {
                    log.warn("投影重建失败（服务仍可用，可手动重建）：{}", error.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("提交投影重建任务失败（服务仍可用）：{}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}

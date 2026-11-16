package com.school.ontology.core.model;

import com.school.ontology.repository.OntologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 启动自愈（07 增量设计 §8）：只在「定义哈希变了」时登记新版本并触发一次重建。 */
class DefinitionActivationRunnerTest {

    private ModelDefinitionLoader loader;
    private OntologyRepository repository;
    private String sourceId;
    private String currentVersion;
    private String currentHash;

    @BeforeEach
    void setUp() {
        loader = new ModelDefinitionLoader();
        repository = mock(OntologyRepository.class);
        sourceId = loader.registry().sourceId();
        currentVersion = loader.mappingVersion().mappingVersion;
        currentHash = "sha256:" + loader.definitionHash();
    }

    @Test
    void 首次启动只登记版本不重建() {
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.of());
        AtomicInteger rebuilds = new AtomicInteger();
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, rebuilds::incrementAndGet);

        runner.run(null);

        assertFalse(runner.needRebuild(), "首次启动不越权重建投影（交给全量导入）");
        verify(repository).upsertMappingVersion(eq(sourceId), eq(currentVersion), eq(currentHash),
                eq("ACTIVE"), eq("system"), eq("startup-baseline"), eq(loader.definitionSource()));
        assertEquals(0, rebuilds.get());
    }

    @Test
    void 哈希一致时既不重建也不改状态() {
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", currentVersion, "definition_hash", currentHash));
        AtomicInteger rebuilds = new AtomicInteger();
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, rebuilds::incrementAndGet);

        runner.run(null);
        runner.onApplicationReady();

        assertFalse(runner.needRebuild(), "普通重启不应触发重建");
        verify(repository, never()).supersedeOtherActive(anyString(), anyString());
        verify(repository, never()).upsertMappingVersion(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        assertEquals(0, rebuilds.get());
    }

    @Test
    void 哈希不一致时登记新ACTIVE并把旧版本置为SUPERSEDED() throws Exception {
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", "school-panorama:000000000000",
                "definition_hash", "sha256:deadbeef"));
        CountDownLatch rebuilt = new CountDownLatch(1);
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, rebuilt::countDown);

        runner.run(null);
        assertTrue(runner.needRebuild(), "定义变了就必须重建当前投影");
        verify(repository).supersedeOtherActive(sourceId, currentVersion);
        verify(repository).upsertMappingVersion(eq(sourceId), eq(currentVersion), eq(currentHash),
                eq("ACTIVE"), eq("system"), eq("startup-baseline"),
                eq(loader.definitionSource()));

        runner.onApplicationReady();
        assertTrue(rebuilt.await(10, TimeUnit.SECONDS), "服务就绪后应异步触发一次重建");
        assertFalse(runner.needRebuild(), "触发后要清标记，避免重复重建");
    }

    @Test
    void 重建失败不影响服务可用() throws Exception {
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", "school-panorama:000000000000",
                "definition_hash", "sha256:deadbeef"));
        CountDownLatch attempted = new CountDownLatch(1);
        DefinitionActivationRunner runner = new DefinitionActivationRunner(loader, repository, () -> {
            attempted.countDown();
            throw new IllegalStateException("来源业务库不可用，无法重建当前投影");
        });

        runner.run(null);
        assertDoesNotThrow(runner::onApplicationReady, "重建失败绝不能向上抛，服务必须仍然可用");
        assertTrue(attempted.await(10, TimeUnit.SECONDS), "重建动作应被尝试执行");
    }

    @Test
    void 本体库不可达时不阻断启动() {
        when(repository.activeMappingVersion(sourceId)).thenThrow(new IllegalStateException("连不上库"));
        DefinitionActivationRunner runner = new DefinitionActivationRunner(loader, repository, () -> {
        });

        assertDoesNotThrow(() -> runner.run(null));
        assertFalse(runner.needRebuild(), "库不可达时按「无需重建」处理，等下一轮重启再比对");
    }

    // ── P7-1 / P7-2：被中断任务的清理与强制重建 ──────────────────────────────

    @Test
    void 启动时清理孤儿RUNNING任务() {
        when(repository.markInterruptedTasks(anyString())).thenReturn(1);
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", currentVersion, "definition_hash", currentHash));
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, () -> {
        });

        runner.run(null);

        verify(repository).markInterruptedTasks(anyString());
    }

    @Test
    void 哈希一致但上一轮任务被中断时强制重建() throws Exception {
        // P7-2 现场：重建被 System.exit 打断，投影只剩 38/95 个对象，而定义哈希完全没变。
        // 只看哈希会跳过重建，残缺就永久保留且对外仍显示 SYNCED。
        when(repository.markInterruptedTasks(anyString())).thenReturn(1);
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", currentVersion, "definition_hash", currentHash));
        CountDownLatch rebuilt = new CountDownLatch(1);
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, rebuilt::countDown);

        runner.run(null);
        assertTrue(runner.needRebuild(), "被中断过就必须重建，哪怕定义哈希没变");
        verify(repository).markInterruptedTasks(anyString());

        runner.onApplicationReady();
        assertTrue(rebuilt.await(10, TimeUnit.SECONDS), "服务就绪后应真的触发重建");
    }

    @Test
    void 无被中断任务且哈希一致时不重建() {
        // 反证：不能因为加了 P7-2 就退化成「每次重启都重建一遍投影」。
        when(repository.markInterruptedTasks(anyString())).thenReturn(0);
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.<String, Object>of(
                "mapping_version", currentVersion, "definition_hash", currentHash));
        AtomicInteger rebuilds = new AtomicInteger();
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, rebuilds::incrementAndGet);

        runner.run(null);
        runner.onApplicationReady();

        assertFalse(runner.needRebuild(), "普通重启不应重建");
        assertEquals(0, rebuilds.get(), "普通重启不应执行重建动作");
    }

    @Test
    void 首次启动但上一轮导入被中断时也强制重建() {
        when(repository.markInterruptedTasks(anyString())).thenReturn(1);
        when(repository.activeMappingVersion(sourceId)).thenReturn(Map.of());
        DefinitionActivationRunner runner = new DefinitionActivationRunner(
                loader, repository, () -> {
        });

        runner.run(null);

        assertTrue(runner.needRebuild(), "首次导入没跑完同样会留下残缺投影");
    }
}

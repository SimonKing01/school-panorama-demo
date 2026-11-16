package com.school.ontology.core.model;

import com.school.ontology.common.ApiException;
import com.school.ontology.repository.OntologyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 应用草稿（07 增量设计 §6.6）：noChange / 待手动重启 / 202 自重启 / 版本冲突。
 *
 * <p><b>红线</b>：单元测试绝不能真的调到 {@code System.exit}，因此本测试一律用
 * 包内可见构造注入「只记录不退出」的动作。</p>
 */
class DefinitionApplyServiceTest {

    @TempDir
    Path directory;

    private ModelDefinitionLoader loader;
    private DefinitionFileStore store;
    private OntologyRepository repository;
    private String baseVersion;

    @BeforeEach
    void setUp() {
        // 先建目录仓储、再建 loader：此时磁盘上没有 overlay，进程加载的就是打包基线。
        // 需要模拟「进程已加载 overlay」的用例（见下面两例）会自己再造一个 loader。
        store = new DefinitionFileStore(directory);
        loader = new ModelDefinitionLoader(store);
        repository = mock(OntologyRepository.class);
        baseVersion = loader.mappingVersion().mappingVersion;
        when(repository.activeMappingVersion(anyString()))
                .thenReturn(Map.<String, Object>of("mapping_version", baseVersion));
        when(repository.hasRunningTask(anyString())).thenReturn(false);
    }

    @Test
    void 没有在线改动时返回noChange且不重启() {
        DefinitionApplyService service = service(false, () -> {
            throw new AssertionError("无改动分支绝不能重启服务");
        });

        Map<String, Object> result = service.apply(request());

        assertEquals(Boolean.FALSE, result.get("applying"));
        assertEquals(Boolean.TRUE, result.get("noChange"));
        assertFalse(service.applying(), "无改动必须释放 applying 标记");
    }

    @Test
    void 恢复基线后再次应用的目标是打包基线版本() {
        // 真实场景：进程已加载在线版本（overlay 生效中），用户点「恢复基线」→ overlay 被删掉，
        // 但 DB 里的 ACTIVE 还是上一轮的在线版本。此时必须重启回基线。
        store.writeOverlay(editedRelationText());
        ModelDefinitionLoader onlineLoader = new ModelDefinitionLoader(store);
        String onlineVersion = onlineLoader.mappingVersion().mappingVersion;
        assertNotEquals(baseVersion, onlineVersion, "测试前提：overlay 必须真的改变了版本");
        store.deleteOverlay();

        when(repository.activeMappingVersion(anyString()))
                .thenReturn(Map.<String, Object>of("mapping_version", onlineVersion));
        RelationEditRequest request = request();
        request.baseMappingVersion = onlineVersion;
        CountDownLatch exited = new CountDownLatch(1);
        DefinitionApplyService service = new DefinitionApplyService(
                onlineLoader, store, repository, true, exited::countDown);

        Map<String, Object> result = service.apply(request);

        assertEquals(Boolean.TRUE, result.get("applying"));
        assertEquals(baseVersion, result.get("targetMappingVersion"),
                "没有 overlay 时目标就是打包基线版本（否则「恢复基线」无法应用）");
    }

    @Test
    void 草稿与生效版本一致时返回noChange() {
        store.writeOverlay(loader.effectiveRelationText());
        DefinitionApplyService service = service(true, () -> {
            throw new AssertionError("noChange 分支绝不能重启服务");
        });

        Map<String, Object> result = service.apply(request());

        assertEquals(Boolean.FALSE, result.get("applying"));
        assertEquals(Boolean.TRUE, result.get("noChange"));
        assertFalse(service.applying());
    }

    @Test
    void 目标已是本进程加载的定义时返回noChange且不重启() {
        // P7-3 现场：重启后约 100ms 的窗口——Tomcat 已就绪、进程已加载新 overlay，
        // 但 DefinitionActivationRunner 还没把 DB 的 ACTIVE 写成新版本。
        // 只比 DB 会把「重启后什么都没变」误判成需要重启，再排一次 800ms 退出，
        // 把启动自愈正在跑的投影重建打断在半路（实测：task 28 永久 RUNNING、投影残 38/95）。
        store.writeOverlay(editedRelationText());
        ModelDefinitionLoader onlineLoader = new ModelDefinitionLoader(store);
        String onlineVersion = onlineLoader.mappingVersion().mappingVersion;
        when(repository.activeMappingVersion(anyString()))
                .thenReturn(Map.<String, Object>of("mapping_version", baseVersion));
        AtomicInteger exits = new AtomicInteger();
        DefinitionApplyService service = new DefinitionApplyService(
                onlineLoader, store, repository, true, exits::incrementAndGet);

        Map<String, Object> result = service.apply(request());

        assertEquals(Boolean.FALSE, result.get("applying"));
        assertEquals(Boolean.TRUE, result.get("noChange"));
        assertEquals(onlineVersion, result.get("targetMappingVersion"));
        assertEquals(0, exits.get(), "进程已加载目标定义，再重启不可能改变任何东西");
        assertFalse(service.applying());
    }

    @Test
    void 自重启关闭时降级为待手动重启() {
        store.writeOverlay(editedRelationText());
        DefinitionApplyService service = service(false, () -> {
            throw new AssertionError("自重启关闭时绝不能退出进程");
        });

        Map<String, Object> result = service.apply(request());

        assertEquals(Boolean.FALSE, result.get("applying"));
        assertEquals(Boolean.TRUE, result.get("pendingRestart"));
        assertNotEquals(baseVersion, result.get("targetMappingVersion"));
        assertTrue(String.valueOf(result.get("message")).contains("手动重启"),
                "实际：" + result.get("message"));
        assertFalse(service.applying());
    }

    @Test
    void 自重启开启时返回202目标版本并延迟执行退出动作() throws Exception {
        store.writeOverlay(editedRelationText());
        CountDownLatch exited = new CountDownLatch(1);
        DefinitionApplyService service = service(true, exited::countDown);

        Map<String, Object> result = service.apply(request());

        assertEquals(Boolean.TRUE, result.get("applying"));
        assertEquals(Boolean.TRUE, result.get("restartScheduled"));
        assertTrue(service.applying(), "退出前保持 applying，防止重复触发重启");
        assertTrue(exited.await(5, TimeUnit.SECONDS), "约 800ms 后应执行退出动作");
    }

    @Test
    void 过期baseMappingVersion返回409() {
        store.writeOverlay(editedRelationText());
        DefinitionApplyService service = service(true, () -> {
        });
        RelationEditRequest request = request();
        request.baseMappingVersion = "school-panorama:deadbeef0000";

        ApiException error = assertThrows(ApiException.class, () -> service.apply(request));

        assertEquals(409, error.httpStatus);
        assertEquals("VERSION_CONFLICT", error.code);
        assertFalse(service.applying());
    }

    @Test
    void 有导入任务运行时返回409() {
        store.writeOverlay(editedRelationText());
        when(repository.hasRunningTask("REBUILD")).thenReturn(true);
        DefinitionApplyService service = service(true, () -> {
        });

        ApiException error = assertThrows(ApiException.class, () -> service.apply(request()));

        assertEquals(409, error.httpStatus);
        assertEquals("TASK_CONFLICT", error.code);
    }

    // ── 辅助 ────────────────────────────────────────────────────────────────

    private DefinitionApplyService service(boolean selfRestartEnabled, Runnable restartAction) {
        return new DefinitionApplyService(loader, store, repository, selfRestartEnabled, restartAction);
    }

    private RelationEditRequest request() {
        RelationEditRequest request = new RelationEditRequest();
        request.baseMappingVersion = baseVersion;
        request.operator = "测试";
        return request;
    }

    private String editedRelationText() {
        return loader.effectiveRelationText()
                .replace("displayName: 授课安排-授课教师", "displayName: 授课教师（在线）");
    }
}

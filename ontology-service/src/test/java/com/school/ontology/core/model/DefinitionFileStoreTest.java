package com.school.ontology.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** overlay 文件层（07 增量设计 §4）：原子写、备份保留、坏文件隔离、并发串行。 */
class DefinitionFileStoreTest {

    @TempDir
    Path directory;

    @Test
    void 原子写入后读到新内容并保留上一版备份() throws Exception {
        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay("version: 1\nrelationTypes: []\n");
        store.writeOverlay("version: 2\nrelationTypes: []\n");

        assertEquals("version: 2\nrelationTypes: []\n", store.readOverlayText());
        List<Path> backups = store.backups();
        assertEquals(1, backups.size(), "每次写前都要备份上一版，实际：" + backups);
        assertEquals("version: 1\nrelationTypes: []\n",
                Files.readString(backups.get(0), StandardCharsets.UTF_8));
    }

    @Test
    void 备份只保留最近五份() throws Exception {
        DefinitionFileStore store = new DefinitionFileStore(directory);
        for (int i = 0; i < 8; i++) {
            store.writeOverlay("version: " + i + "\nrelationTypes: []\n");
        }
        assertEquals(5, store.backups().size(), "备份只保留最近 5 份，避免无限增长");
    }

    @Test
    void 非法overlay被改名隔离并记录回退原因() throws Exception {
        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay("version: 1\nrelationTypes: []\n");

        Path quarantined = store.quarantineInvalidOverlay("overlay 缺少必填字段");

        assertFalse(store.overlayExists(), "坏文件必须离开原位，避免下次启动再踩一次");
        assertNotNull(quarantined, "隔离应返回改名后的文件路径");
        assertTrue(quarantined.getFileName().toString()
                .startsWith(DefinitionFileStore.OVERLAY_FILE + ".invalid."), "实际：" + quarantined);
        assertTrue(store.lastFallback().occurred(), "回退必须留痕供管理页展示");
        assertEquals("overlay 缺少必填字段", store.lastFallback().reason());
    }

    @Test
    void 删除overlay返回是否真的删掉了() {
        DefinitionFileStore store = new DefinitionFileStore(directory);
        assertFalse(store.deleteOverlay(), "没有 overlay 时返回 false");
        store.writeOverlay("version: 1\nrelationTypes: []\n");
        assertTrue(store.deleteOverlay(), "存在 overlay 时应删除并返回 true");
        assertFalse(store.overlayExists());
    }

    @Test
    void 并发写串行且不留下临时文件() throws Exception {
        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay("version: 0\nrelationTypes: []\n");
        int threads = 4;
        int writesPerThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> written = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String content = "version: " + (t + 1) + "\nrelationTypes: []\n";
            synchronized (written) {
                written.add(content);
            }
            pool.submit(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < writesPerThread; i++) {
                        store.writeOverlay(content);
                    }
                } catch (Throwable error) {
                    synchronized (failures) {
                        failures.add(error);
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写应在超时前全部完成");
        pool.shutdownNow();

        assertTrue(failures.isEmpty(), "并发写不应失败：" + failures);
        String finalText = store.readOverlayText();
        assertTrue(written.contains(finalText), "最终内容必须是某一次完整写入，实际：" + finalText);
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().contains(".tmp.")),
                    "原子写不应留下临时文件");
        }
    }

    @Test
    void 目录不存在时会自动创建() throws IOException {
        Path nested = directory.resolve("data/ontology");
        DefinitionFileStore store = new DefinitionFileStore(nested);
        store.writeOverlay("version: 1\nrelationTypes: []\n");
        assertTrue(Files.isRegularFile(nested.resolve(DefinitionFileStore.OVERLAY_FILE)));
    }
}

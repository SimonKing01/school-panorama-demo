package com.school.ontology.core.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双闸门（07 增量设计 §5.3）：
 * overlay 优先、overlay 非法回退基线并隔离坏文件、overlay 与基线同文则版本不变。
 */
class ModelDefinitionLoaderOverlayTest {

    @TempDir
    Path directory;

    @Test
    void 无overlay时来源是打包基线() {
        ModelDefinitionLoader loader = new ModelDefinitionLoader(new DefinitionFileStore(directory));
        assertEquals("BASELINE", loader.definitionSource());
        assertEquals(loader.baselineMappingVersion().mappingVersion,
                loader.mappingVersion().mappingVersion,
                "没有在线改动时，生效版本必须等于打包基线版本");
    }

    @Test
    void overlay存在时优先生效且哈希与版本随内容变化() {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        String edited = baseline.effectiveRelationText()
                .replace("displayName: 授课安排-授课教师", "displayName: 授课教师（在线）");
        assertNotEquals(baseline.effectiveRelationText(), edited, "测试前提：基线文本可被替换");

        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay(edited);

        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);

        assertEquals("OVERLAY", loader.definitionSource());
        assertEquals("授课教师（在线）", loader.registry().relationOf("session_taught_by").displayName,
                "overlay 必须真正驱动 ModelRegistry");
        assertNotEquals(baseline.definitionHash(), loader.definitionHash(),
                "hash 必须覆盖实际生效的关系文本（否则版本不会变）");
        assertNotEquals(baseline.mappingVersion().mappingVersion,
                loader.mappingVersion().mappingVersion, "改一个字段就必须换一个 mappingVersion");
        assertEquals(baseline.mappingVersion().mappingVersion,
                loader.baselineMappingVersion().mappingVersion,
                "基线版本不随之改变，「恢复基线」要靠它判断");
        assertEquals(10, loader.registry().relations().size(), "overlay 是整份替换，条数不变");
    }

    @Test
    void overlay与基线内容完全相同时视为无改动() {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay(baseline.effectiveRelationText());

        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);

        assertEquals("OVERLAY", loader.definitionSource(), "文件存在即走 overlay 通道");
        assertEquals(baseline.mappingVersion().mappingVersion, loader.mappingVersion().mappingVersion,
                "内容相同则 hash 相同 → 版本相同 → 前端判为 noChange");
    }

    @Test
    void overlay非法时回退基线并隔离坏文件() throws Exception {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        DefinitionFileStore store = new DefinitionFileStore(directory);
        // 缺 generation / 展示名 / 起止类型：解析能过，校验必挂。
        store.writeOverlay("version: 1\nrelationTypes:\n  - id: broken_relation\n    displayName: 坏定义\n");

        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);

        assertEquals("OVERLAY_FALLBACK", loader.definitionSource(),
                "overlay 非法必须回退基线，绝不锁死服务");
        assertEquals(baseline.mappingVersion().mappingVersion, loader.mappingVersion().mappingVersion,
                "回退后生效版本必须等于打包基线版本");
        assertTrue(loader.registry().hasRelation("session_taught_by"), "回退后基线定义仍完整可用");
        assertFalse(store.overlayExists(), "坏文件必须被隔离，避免下次启动重复回退");
        assertTrue(store.lastFallback().occurred());
        assertTrue(store.lastFallback().reason().contains("起止类型"),
                "回退原因应带上具体校验错误，实际：" + store.lastFallback().reason());
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString()
                    .startsWith(DefinitionFileStore.OVERLAY_FILE + ".invalid.")), "必须留下隔离证据");
        }
    }

    @Test
    void overlay无法解析时同样回退基线() {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        DefinitionFileStore store = new DefinitionFileStore(directory);
        store.writeOverlay("version: 1\nrelationTypes: 这不是一个列表\n");

        ModelDefinitionLoader loader = new ModelDefinitionLoader(store);

        assertEquals("OVERLAY_FALLBACK", loader.definitionSource(), "解析失败也属于 overlay 非法");
        assertEquals(baseline.mappingVersion().mappingVersion, loader.mappingVersion().mappingVersion);
    }

    @Test
    void 对象overlay合法时优先生效且hash覆盖对象文本() {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        String edited = baseline.objectText()
                .replace("    displayName: 学生\n", "    displayName: 学生（在线）\n");
        assertNotEquals(baseline.objectText(), edited, "测试前提：基线对象文本可被替换");
        DefinitionFileStore relationStore = new DefinitionFileStore(directory, DefinitionFileStore.OVERLAY_FILE);
        DefinitionFileStore objectStore = new DefinitionFileStore(directory, DefinitionFileStore.OBJECT_OVERLAY_FILE);
        objectStore.writeOverlay(edited);

        ModelDefinitionLoader loader = new ModelDefinitionLoader(relationStore, objectStore);

        assertEquals("OVERLAY", loader.definitionSource());
        assertEquals("OVERLAY", loader.objectDefinitionSource());
        assertEquals("BASELINE", loader.relationDefinitionSource());
        assertEquals("学生（在线）", loader.registry().typeOf("student").displayName,
                "对象 overlay 必须真正驱动 ModelRegistry");
        assertNotEquals(baseline.definitionHash(), loader.definitionHash(),
                "definitionHash 必须覆盖实际生效的对象定义文本");
        assertNotEquals(baseline.mappingVersion().mappingVersion, loader.mappingVersion().mappingVersion);
        assertEquals(baseline.mappingVersion().mappingVersion,
                loader.baselineMappingVersion().mappingVersion, "基线版本不受 overlay 影响");
        assertEquals(7, loader.registry().types().size(), "overlay 是整份替换，对象类型条数不变");
    }

    @Test
    void 对象overlay非法时回退基线且不影响关系overlay() throws Exception {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        DefinitionFileStore relationStore = new DefinitionFileStore(directory, DefinitionFileStore.OVERLAY_FILE);
        DefinitionFileStore objectStore = new DefinitionFileStore(directory, DefinitionFileStore.OBJECT_OVERLAY_FILE);
        String editedRelation = baseline.effectiveRelationText()
                .replace("displayName: 授课安排-授课教师", "displayName: 授课教师（在线）");
        relationStore.writeOverlay(editedRelation);
        // 缺 identity / attributes / titleTemplate：解析能过，集成校验必挂。
        objectStore.writeOverlay("version: 1\nobjectTypes:\n  - id: broken_object\n    displayName: 坏对象\n");

        ModelDefinitionLoader loader = new ModelDefinitionLoader(relationStore, objectStore);

        assertEquals("OVERLAY_FALLBACK", loader.definitionSource());
        assertEquals("学生", loader.registry().typeOf("student").displayName, "坏对象定义必须回退基线");
        assertEquals("授课教师（在线）", loader.registry().relationOf("session_taught_by").displayName,
                "对象 overlay 回退不能牵连合法的关系 overlay");
        assertFalse(objectStore.overlayExists(), "坏对象 overlay 必须被隔离");
        assertTrue(relationStore.overlayExists(), "合法关系 overlay 必须保留");
        assertTrue(objectStore.lastFallback().occurred());
        assertTrue(objectStore.lastFallback().reason().contains("属性"),
                "回退原因应带具体校验错误，实际：" + objectStore.lastFallback().reason());
        assertEquals(DefinitionFileStore.OBJECT_OVERLAY_FILE, objectStore.lastFallback().fileName());
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString()
                    .startsWith(DefinitionFileStore.OBJECT_OVERLAY_FILE + ".invalid.")), "必须留下隔离证据");
        }
    }

    @Test
    void 两份overlay同时存在时hash覆盖两份文本() {
        ModelDefinitionLoader baseline = new ModelDefinitionLoader();
        DefinitionFileStore relationStore = new DefinitionFileStore(directory, DefinitionFileStore.OVERLAY_FILE);
        DefinitionFileStore objectStore = new DefinitionFileStore(directory, DefinitionFileStore.OBJECT_OVERLAY_FILE);
        String editedRelation = baseline.effectiveRelationText()
                .replace("displayName: 授课安排-授课教师", "displayName: 授课教师（在线）");
        String editedObject = baseline.objectText()
                .replace("    displayName: 学生\n", "    displayName: 学生（在线）\n");
        relationStore.writeOverlay(editedRelation);
        objectStore.writeOverlay(editedObject);

        ModelDefinitionLoader loader = new ModelDefinitionLoader(relationStore, objectStore);

        assertEquals("OVERLAY", loader.definitionSource());
        assertEquals("学生（在线）", loader.registry().typeOf("student").displayName);
        assertEquals("授课教师（在线）", loader.registry().relationOf("session_taught_by").displayName);
        assertEquals("sha256:" + com.school.ontology.common.Resp.sha256(
                        editedObject + "\n" + editedRelation + "\n" + loader.sourceText()),
                "sha256:" + loader.definitionHash(), "hash 必须同时覆盖两份 overlay 文本");
    }

    @Test
    void 打包基线自身非法时仍然拒绝启动() {
        // 基线非法必须抛 IllegalStateException（B1 口径不变）：这里直接对基线文本做负例校验，
        // 避免为了造一个坏 classpath 文件而污染共享测试资源。
        ModelDefinitionLoader loader = new ModelDefinitionLoader();
        ModelDocument.RelationTypeDocument broken = new ModelDocument.RelationTypeDocument();
        broken.version = 1;
        broken.relationTypes.add(new RelationTypeDef());
        assertFalse(loader.validate(loader.objectDocument(), broken,
                loader.sourceDocument()).isEmpty(), "空关系定义必须校验失败");
    }
}

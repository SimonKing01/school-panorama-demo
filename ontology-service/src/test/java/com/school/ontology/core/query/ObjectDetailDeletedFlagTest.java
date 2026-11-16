package com.school.ontology.core.query;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.spi.SpiRegistry;
import com.school.ontology.repository.OntologyRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷回归（P1）：{@code GET /objects/{globalId}} 不暴露 deleted。
 *
 * <p>墓碑对象仍可查（便于追溯「它曾经是什么」），但响应必须显式给出 {@code deleted}，
 * 否则前端与验收都无法区分「对象存在」与「已在来源业务库删除」。
 * 墓碑前的最后快照仍要完整返回，不能用 404 掩盖。</p>
 */
class ObjectDetailDeletedFlagTest {

    private static final String GID = "classroom:3";
    private static final LocalDateTime QUERY_TIME = LocalDateTime.of(2026, 11, 14, 12, 0);

    /** 详情查询用到的 object 行（列名与 JdbcTemplate 返回的 snake_case 一致）。 */
    private static Map<String, Object> objectRow(boolean deleted) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("object_type", "classroom");
        row.put("title", "高二(3)班教室");
        row.put("attributes", "{}");
        row.put("mapping_version", "mv-1");
        row.put("deleted", deleted);
        return row;
    }

    private static ObjectTypeDef classroomType() {
        ObjectTypeDef def = new ObjectTypeDef();
        def.id = "classroom";
        def.displayName = "教室";
        return def;
    }

    private static OntologyQueryService service(OntologyRepository repository) {
        ModelRegistry registry = mock(ModelRegistry.class);
        when(registry.typeOf("classroom")).thenReturn(classroomType());
        ChangeQueryService changeQuery = mock(ChangeQueryService.class);
        when(changeQuery.changes(anyString(), any(), anyInt(), anyInt()))
                .thenReturn(Resp.map("page", 1, "size", 5, "total", 0, "items", List.of()));
        return new OntologyQueryService(repository, registry,
                mock(ModelDefinitionLoader.class), mock(SpiRegistry.class),
                mock(DynamicResolverDispatcher.class), mock(GraphService.class),
                changeQuery, mock(OverviewService.class), "http://localhost:5180");
    }

    private static OntologyRepository repositoryWith(boolean deleted) {
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.objectRow(GID)).thenReturn(objectRow(deleted));
        when(repository.parseMap(any())).thenReturn(Map.of());
        when(repository.relationsFrom(anyString(), anyBoolean())).thenReturn(List.of());
        when(repository.relationsTo(anyString(), anyBoolean())).thenReturn(List.of());
        when(repository.sourceRefsOf(anyString())).thenReturn(List.of());
        return repository;
    }

    @Test
    void 存活对象详情返回deleted为false() {
        Map<String, Object> detail = service(repositoryWith(false)).detail(GID, QUERY_TIME);

        assertTrue(detail.containsKey("deleted"), "响应必须包含 deleted 字段（前端据此区分墓碑）");
        assertEquals(Boolean.FALSE, detail.get("deleted"));
        assertNull(detail.get("deletedHint"), "存活对象不需要墓碑提示");
        assertEquals("高二(3)班教室", detail.get("title"));
        assertEquals("classroom", detail.get("type"));
    }

    @Test
    void 墓碑对象详情返回deleted为true且仍可查到最后快照() {
        Map<String, Object> detail = service(repositoryWith(true)).detail(GID, QUERY_TIME);

        assertEquals(Boolean.TRUE, detail.get("deleted"), "墓碑对象必须显式标记 deleted=true");
        assertNotNull(detail.get("deletedHint"), "墓碑对象要给出可读提示");
        // 墓碑不是 404：最后快照仍要返回，供追溯「它曾经是什么」。
        assertEquals("高二(3)班教室", detail.get("title"));
        assertEquals("mv-1", detail.get("mappingVersion"));
        assertEquals(GID, detail.get("globalId"));
    }
}

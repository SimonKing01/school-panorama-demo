package com.school.ontology.core.sync;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.ChangeUnit;
import com.school.ontology.core.projection.EventPlan;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.projection.SnapshotBuilder;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.core.spi.SourceAdapter;
import com.school.ontology.core.spi.SourceQuery;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件计划解析器：依据事件负载与只读业务库生成投影变更计划。
 *
 * <p>事件携带的 revision 作为版本水位；演示业务时间只用于 CLOCK 更新
 * ont_data_source.demo_time，绝不写进处理时间字段。</p>
 */
@Component
public class SourceDbEventSnapshotResolver implements EventSnapshotResolver {

    private final SourceAdapter adapter;
    private final ModelRegistry registry;
    private final ModelDefinitionLoader loader;
    private final SnapshotBuilder builder;
    private final SnapshotSource snapshotSource;

    public SourceDbEventSnapshotResolver(SourceAdapter adapter, ModelRegistry registry,
                                         ModelDefinitionLoader loader, SnapshotBuilder builder,
                                         SnapshotSource snapshotSource) {
        this.adapter = adapter;
        this.registry = registry;
        this.loader = loader;
        this.builder = builder;
        this.snapshotSource = snapshotSource;
    }

    @Override
    public EventPlan plan(OutboxEvent event) {
        Map<String, Object> payload = event.payload();
        String kind = event.changeKind() == null ? "" : event.changeKind();
        String mappingVersion = loader.mappingVersion().mappingVersion;
        MappingContext ctx = MappingContext.of(registry.sourceId(), mappingVersion,
                event.revision(), adapter.lookups());

        if ("CLOCK".equals(kind)) {
            return new EventPlan(List.of(), List.of(), demoTimeUpdate(payload),
                    List.of(), List.of(), Map.of(), List.of(), List.of());
        }
        if (isFullSnapshotHint(kind, payload)) {
            // SYNC_TODAY / RESET 只给一条全量提示事件，底座重扫全部对象类型；
            // 不把它们伪装成逐条业务变更历史（不伪造操作人/发生时间）。
            List<SnapshotBatch> batches = new ArrayList<>(snapshotSource.snapshot(event.revision()).values());
            return new EventPlan(batches, List.of(), Map.of(), List.of(), List.of(),
                    changeInfo(kind, payload, mappingVersion), List.of(), List.of());
        }
        if ("DELETE".equals(kind)) {
            List<String> tombstones = new ArrayList<>();
            for (Map<String, Object> item : itemsOf(payload)) {
                String gid = globalIdOf(item);
                if (gid != null) {
                    tombstones.add(gid);
                }
            }
            return new EventPlan(List.of(), tombstones, Map.of(), tombstones, List.of(),
                    changeInfo(kind, payload, mappingVersion), List.of(), List.of());
        }

        List<SnapshotBatch> batches = new ArrayList<>();
        List<String> selfGlobalIds = new ArrayList<>();
        List<String> tombstones = new ArrayList<>();
        // 逐条目变更单元：批量事件（BATCH_ADJUST）的 items[] 每条都有
        // before/after/diff，而顶层 diff 是 allDiffs（所有条目合并）。
        // 这里逐条留一份，ProjectionWriter 才能把变更历史按条目拆开。
        List<ChangeUnit> changeUnits = new ArrayList<>();
        for (Map<String, Object> item : itemsOf(payload)) {
            String sourceTable = text(item.get("sourceTable"));
            String sourcePk = text(item.get("sourcePk"));
            if (sourceTable == null || sourcePk == null || !registry.hasTypeByTable(sourceTable)) {
                continue;
            }
            String objectType = registry.typeBySourceTable(sourceTable);
            List<SourceRecord> records = adapter.scan(SourceQuery.byPk(objectType, sourcePk));
            if (records.isEmpty()) {
                tombstones.add(GlobalIds.gid(registry.sourceId(), objectType, sourcePk));
                continue;
            }
            SnapshotBatch batch = builder.buildAll(records, objectType, ctx);
            batches.add(batch);
            for (var row : batch.objects()) {
                selfGlobalIds.add(row.globalId());
            }
            changeUnits.add(new ChangeUnit(GlobalIds.gid(registry.sourceId(), objectType, sourcePk),
                    mapOf(item.get("before")), mapOf(item.get("after")),
                    listOfMap(item.get("diff")), sourceTable, sourcePk,
                    text(item.get("reason"))));
        }

        // 邻居刷新：事件只重建「自身对象」，但关系换边后旧/新邻居的派生属性（如教室的排课数、
        // 教师的任教班级）会滞后。这里按事件给出的相关对象 gid 重扫来源库，放进 refreshBatches
        // （只写对象与来源引用、关系一律不动）；这些对象不进 selfGlobalIds
        // （业务状态未改变，只写 DERIVED_RELATED 影响）。
        // 另有 derivedRefreshBatches：邻居里**声明了 JOIN_RULE 派生关系**的对象，其派生边必须
        // 一起重算（调课换教师后学生的 student_taught_by 要立刻换边，见 P8），
        // 但走的是独立通道，只动派生关系类型，外键关系零影响。
        List<String> related = new ArrayList<>(new LinkedHashSet<>(relatedGlobalIds(payload)));
        List<SnapshotBatch> refreshBatches = new ArrayList<>();
        List<SnapshotBatch> derivedRefreshBatches = new ArrayList<>();
        Set<String> handled = new LinkedHashSet<>(selfGlobalIds);
        for (String globalId : related) {
            if (globalId == null || !handled.add(globalId)) {
                continue;
            }
            RelatedRefresh refresh = relatedRefresh(globalId, ctx);
            if (refresh == null) {
                continue;
            }
            refreshBatches.add(refresh.properties());
            if (refresh.derived() != null) {
                derivedRefreshBatches.add(refresh.derived());
            }
        }
        return new EventPlan(batches, tombstones, Map.of(), selfGlobalIds,
                related, changeInfo(kind, payload, mappingVersion), refreshBatches, changeUnits,
                derivedRefreshBatches);
    }

    /**
     * 相关对象的两种刷新批次。
     *
     * @param properties 属性刷新批次：只带对象与来源引用，relations() 恒为空
     * @param derived    派生关系批次：只带 JOIN_RULE 关系；该对象类型没有派生规则时为 {@code null}
     */
    private record RelatedRefresh(SnapshotBatch properties, SnapshotBatch derived) {
    }

    /**
     * 相关对象的刷新批次：解析 gid → 只读重扫来源行 → 构建投影。
     *
     * <p>属性批次刻意只保留 objects() 与 sourceRefs()：
     * 一是不让邻居批次的外键关系与自身批次互相覆盖（关系只会抖动）；
     * 二是来源行已消失时返回 null 而不是墓碑——邻居不存在不等于被删除，绝不误标墓碑。</p>
     *
     * <p>派生关系批次只保留 {@code generation.kind == JOIN_RULE} 的边：这类关系由联表规则算出，
     * 起点对象即使没有任何字段变化，另一端（教师）换人也会让集合变化，因此必须重算；
     * 外键关系则由起点对象自己的字段决定，只属于「自身批次」的职责，这里继续丢弃。
     * 判断是否派生关系一律问 {@link ModelRegistry} 的定义，不写死任何关系类型标识。</p>
     */
    private RelatedRefresh relatedRefresh(String globalId, MappingContext ctx) {
        // gid 规则固定为 gid:{sourceId}:{objectType}:{sourcePk}，limit=4 让 sourcePk 里的冒号原样保留。
        String[] parts = globalId.split(":", 4);
        if (parts.length != 4 || !"gid".equals(parts[0])) {
            return null;
        }
        if (!registry.sourceId().equals(parts[1])) {
            return null;   // 跨源 gid 一律不处理，避免跨源合并
        }
        String objectType = parts[2];
        String sourcePk = parts[3];
        if (!registry.hasType(objectType) || sourcePk.isBlank()) {
            return null;
        }
        List<SourceRecord> records = adapter.scan(SourceQuery.byPk(objectType, sourcePk));
        if (records.isEmpty()) {
            return null;
        }
        SnapshotBatch built = builder.buildAll(records, objectType, ctx);
        SnapshotBatch properties = new SnapshotBatch(built.objects(), new ArrayList<>(), built.sourceRefs());
        if (!registry.hasDerivedRelationRules(objectType)) {
            // 该类型的出边里没有派生关系，重算毫无意义：不建空批次，避免白跑一次按类型停边。
            return new RelatedRefresh(properties, null);
        }
        List<RelationInstance> derived = new ArrayList<>();
        for (RelationInstance relation : built.relations()) {
            if (registry.isDerivedRelation(relation.relationType())) {
                derived.add(relation);
            }
        }
        // 即使 derived 为空也要建批次：派生集合**变空**同样是一次真实变化
        // （例如该班当天再没有这位老师的课），必须允许落库侧结束旧边。
        return new RelatedRefresh(properties,
                new SnapshotBatch(built.objects(), derived, new ArrayList<>()));
    }

    private boolean isFullSnapshotHint(String kind, Map<String, Object> payload) {
        return "SYNC_TODAY".equals(kind) || "RESET".equals(kind)
                || "FULL_SNAPSHOT_HINT".equals(text(payload.get("kind")));
    }

    private Map<String, Object> demoTimeUpdate(Map<String, Object> payload) {
        Map<String, Object> update = new LinkedHashMap<>();
        LocalDateTime demoAt = parse(payload.containsKey("demoAtAfter")
                ? payload.get("demoAtAfter") : payload.get("demoAt"));
        if (demoAt != null) {
            update.put("demoTime", demoAt);
        }
        Object running = payload.get("running");
        if (running != null) {
            update.put("demoRunning", Boolean.parseBoolean(String.valueOf(running)));
        }
        LocalDateTime anchor = parse(payload.get("anchorRealAt"));
        if (anchor != null) {
            update.put("anchorRealAt", anchor);
        }
        return update;
    }

    private static LocalDateTime parse(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        try {
            return Resp.parseTs(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> changeInfo(String kind, Map<String, Object> payload,
                                           String mappingVersion) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("changeKind", kind);
        info.put("before", payload.getOrDefault("before", Map.of()));
        info.put("after", payload.getOrDefault("after", Map.of()));
        info.put("diff", payload.getOrDefault("diff", List.of()));
        info.put("relationsAdded", payload.getOrDefault("relationsAdded", List.of()));
        info.put("relationsRemoved", payload.getOrDefault("relationsRemoved", List.of()));
        info.put("reason", payload.get("reason"));
        info.put("operator", payload.get("operator"));
        info.put("mappingVersion", mappingVersion);
        return info;
    }

    /** 影响对象：优先取事件显式给出的全局 ID；否则按 impact 中 xxxIds 的命名约定推导。 */
    private List<String> relatedGlobalIds(Map<String, Object> payload) {
        List<String> result = new ArrayList<>();
        Object explicit = payload.get("relatedGlobalIds");
        if (explicit instanceof List<?> list) {
            for (Object value : list) {
                if (value != null) {
                    result.add(String.valueOf(value));
                }
            }
            return result;
        }
        Map<String, Object> impact = eventMap(payload.get("impact"));
        for (Map.Entry<String, Object> entry : impact.entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith("Ids") || !(entry.getValue() instanceof List<?> ids)) {
                continue;
            }
            String objectType = key.substring(0, key.length() - 3);
            if (!registry.hasType(objectType)) {
                continue;
            }
            for (Object id : ids) {
                if (id != null) {
                    result.add(GlobalIds.gid(registry.sourceId(), objectType, id));
                }
            }
        }
        return result;
    }

    private String globalIdOf(Map<String, Object> item) {
        String table = text(item.get("sourceTable"));
        String pk = text(item.get("sourcePk"));
        if (table == null || pk == null || !registry.hasTypeByTable(table)) {
            return null;
        }
        return GlobalIds.gid(registry.sourceId(), registry.typeBySourceTable(table), pk);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> payload) {
        Object items = payload.get("items");
        if (items instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
                }
            }
            return result;
        }
        return List.of(payload);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> eventMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /** 条目级字段快照：缺失或类型不符时给空表，绝不让 null 流进变更历史。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    /** 条目级字段差集：缺失或类型不符时给空表。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMap(Object value) {
        return value instanceof List<?> list
                ? new ArrayList<>((List<Map<String, Object>>) list) : List.of();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

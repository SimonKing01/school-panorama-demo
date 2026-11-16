package com.school.ontology.core.projection;

import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.spi.OutboxEvent;
import com.school.ontology.core.spi.RelationInstance;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 幂等投影写入：快照 / 事件共用同一套「稳定 ID + 条件 UPSERT + 关系重算」逻辑。
 *
 * <p>版本水位：所有写入都带 appliedRevision，存储层的条件 UPSERT 保证
 * 「revision >= applied_revision 才应用」，旧事件/旧快照不覆盖新状态。</p>
 */
@Component
public class ProjectionWriter {

    /** 影响对象的统一说明文案：相关对象只是按规则算出来，业务状态并未改变。 */
    private static final String RELATED_BASIS = "仅为按规则计算出的相关对象，业务状态未改变";

    private final ProjectionStore store;
    private final ModelRegistry registry;

    public ProjectionWriter(ProjectionStore store, ModelRegistry registry) {
        this.store = store;
        this.registry = registry;
    }

    /** 全量导入 / 重建 / 对账修复：把一批快照以指定水位写入当前投影。 */
    public void applySnapshot(SnapshotBatch batch, long watermark) {
        LocalDateTime now = LocalDateTime.now();
        for (ProjectionRow row : batch.objects()) {
            store.upsertObject(row.withRevision(watermark));
        }
        for (SourceRefRow ref : batch.sourceRefs()) {
            store.upsertSourceRef(ref);
        }

        // 关系重算：不在新快照中的旧关系结束（valid_to + active=false），
        // 新关系按稳定 relationId UPSERT。这样「老师替换」能看到旧关系结束与新关系建立。
        Map<String, Set<String>> keepByFrom = new HashMap<>();
        for (RelationInstance relation : batch.relations()) {
            keepByFrom.computeIfAbsent(relation.fromGlobalId(), ignored -> new LinkedHashSet<>())
                    .add(relation.relationId());
        }
        for (ProjectionRow row : batch.objects()) {
            Set<String> keep = keepByFrom.getOrDefault(row.globalId(), Set.of());
            store.deactivateRelationsFromExcept(row.globalId(), keep, now, watermark);
        }
        for (RelationInstance relation : batch.relations()) {
            store.upsertRelation(relation.withRevision(watermark));
        }
    }

    /**
     * 条件 UPSERT：仅当 incoming revision 不低于当前水位时才更新属性。
     * 用于「对账修复」等只改当前投影的场景。
     */
    public boolean compareAndUpsert(String globalId, long revision, Map<String, Object> attributes) {
        ProjectionRow existing = store.object(globalId);
        if (existing == null) {
            return false;
        }
        if (revision < existing.appliedRevision()) {
            return false;
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing.attributes());
        merged.putAll(attributes);
        store.upsertObject(new ProjectionRow(existing.globalId(), existing.sourceId(),
                existing.objectType(), existing.sourcePk(), existing.title(), merged,
                existing.mappingVersion(), revision, false));
        return true;
    }

    /** 墓碑：对象标记删除 + 两端有效关系全部失效，不物理删审计。 */
    public void tombstone(String globalId, long revision) {
        LocalDateTime now = LocalDateTime.now();
        store.tombstoneObject(globalId, revision);
        store.deactivateRelationsFrom(globalId, now, revision);
        store.deactivateRelationsTo(globalId, now, revision);
    }

    /**
     * 相关对象刷新：**只写对象与来源引用，绝不动关系**。
     *
     * <p>刷新批次按设计丢弃了 relations()（邻居的关系不由本次事件重算），因此它绝不能走
     * {@link #applySnapshot(SnapshotBatch, long)}——那条路径会以「保留清单为空」为由，
     * 把该对象的全部出边判成结束（批次④ 的阻断级回归：一次调课误停 60+ 条无关关系）。</p>
     */
    public void applyObjectRefresh(SnapshotBatch batch, long watermark) {
        for (ProjectionRow row : batch.objects()) {
            boolean changed = needsObjectVersion(row);
            store.upsertObject(row.withRevision(watermark));
            if (changed) {
                store.appendObjectVersion(row.globalId(), watermark,
                        new LinkedHashMap<>(row.attributes()), null);
            }
        }
        for (SourceRefRow ref : batch.sourceRefs()) {
            store.upsertSourceRef(ref);
        }
    }

    /**
     * 事件投影落库（在 EventConsumer 的本体库单事务内调用）。
     * 依次写：对象 + 关系 + 对象版本 + 变更历史 + 影响 + 来源引用 + 平台版本。
     *
     * <p>关系增加/解除不依赖来源上报：落库前先对本批涉及的对象采集一次「当前有效关系快照」，
     * 落库后再采集一次，两侧差集即「本次事件真正建立/结束了哪些关系」。这样来源系统（原业务）
     * 不需要知道任何本体关系类型，新增类型时这段逻辑也不用改。</p>
     */
    public long applyEvent(OutboxEvent event, EventPlan plan) {
        long revision = event.revision();
        Map<String, Object> info = plan.changeInfo();
        String primaryGid = plan.selfGlobalIds().isEmpty()
                ? (plan.tombstoneGlobalIds().isEmpty() ? "" : plan.tombstoneGlobalIds().get(0))
                : plan.selfGlobalIds().get(0);
        // 只有会写变更历史的事件才需要关系差集：CLOCK / 全量重扫提示事件不写业务历史，
        // 尤其全量重扫涉及全部对象，避免为此付出「每对象两次关系查询」的代价。
        Set<String> focusGlobalIds = primaryGid.isBlank() ? Set.of() : focusGlobalIds(plan);
        Map<String, RelationInstance> relationsBefore = activeRelations(focusGlobalIds);
        for (SnapshotBatch batch : plan.batches()) {
            // 先在落库前判定「哪些对象属性确有变化」：upsert 之后再比会永远相等。
            List<ProjectionRow> versionedRows = new ArrayList<>();
            for (ProjectionRow row : batch.objects()) {
                if (needsObjectVersion(row)) {
                    versionedRows.add(row);
                }
            }
            applySnapshot(batch, revision);
            for (ProjectionRow row : versionedRows) {
                store.appendObjectVersion(row.globalId(), revision,
                        new LinkedHashMap<>(row.attributes()), event.eventId());
            }
        }
        // 相关对象刷新：只写对象属性与来源引用，绝不重算关系（否则会误停邻居全部出边）。
        for (SnapshotBatch refresh : plan.refreshBatches()) {
            applyObjectRefresh(refresh, revision);
        }
        for (String globalId : plan.tombstoneGlobalIds()) {
            tombstone(globalId, revision);
        }
        if (!plan.demoTimeUpdate().isEmpty()) {
            store.updateDataSource(registry.sourceId(), plan.demoTimeUpdate());
        }
        if (primaryGid.isBlank()) {
            // CLOCK 等只改演示时间的事件不产生对象属性变化，不写业务变更历史。
            store.bumpPlatformRevision();
            return -1L;
        }
        Map<String, RelationInstance> relationsAfter = activeRelations(focusGlobalIds);
        // 底座算出的差集优先；仅当它为空（如人工注入只带 relationsAdded 的构造事件）时回退事件负载。
        Map<String, String> titleCache = new LinkedHashMap<>();
        List<Map<String, Object>> computedAdded =
                relationDiff(relationsBefore, relationsAfter, true, titleCache);
        List<Map<String, Object>> computedRemoved =
                relationDiff(relationsBefore, relationsAfter, false, titleCache);
        // 多条目事件（批量写入口一次提交多条业务变更）必须按条目拆成多条变更历史：
        // 否则一条记录会同时带两个对象的 diff，挂到第一个对象名下，与业务侧逐条 change_records 不一致。
        if (plan.changeUnits().size() > 1) {
            return appendPerUnitChanges(event, plan, computedAdded, computedRemoved, info);
        }
        List<Map<String, Object>> relationsAdded = computedAdded.isEmpty()
                ? listOfMap(info.get("relationsAdded")) : computedAdded;
        List<Map<String, Object>> relationsRemoved = computedRemoved.isEmpty()
                ? listOfMap(info.get("relationsRemoved")) : computedRemoved;

        ChangeRecordRow change = new ChangeRecordRow(event.eventId(), primaryGid,
                stringOf(info.get("changeKind"), event.changeKind()),
                "EVENT", mapOf(info.get("before")), mapOf(info.get("after")),
                listOfMap(info.get("diff")), relationsAdded, relationsRemoved,
                stringOf(info.get("reason"), null), stringOf(info.get("operator"), null),
                event.businessTime(), stringOf(info.get("mappingVersion"), null));
        long changeId = store.appendChangeHistory(change);
        for (String gid : plan.selfGlobalIds()) {
            store.appendImpact(changeId, gid, "SELF_CHANGED", "本身属性或关系发生变化");
        }
        for (String gid : plan.relatedGlobalIds()) {
            store.appendImpact(changeId, gid, "DERIVED_RELATED", RELATED_BASIS);
        }
        store.bumpPlatformRevision();
        return changeId;
    }

    /**
     * 按条目写变更历史：每个 {@link ChangeUnit} 一条记录，只描述自己的对象。
     *
     * <p>关系增删沿用整批差集，但按端点过滤——一条边只写进「真正被它触及」的那个对象的记录里。
     * 影响对象只写本条自身 SELF_CHANGED 与 {@code relatedGlobalIds} 的 DERIVED_RELATED；
     * 同批其它条目**不**写成本条的相关对象（它们各有自己的记录，互相标注会让对方的
     * 变化列表列出本条记录，等于缺陷复现）。平台版本只在全部记录写完后 bump 一次。</p>
     */
    private long appendPerUnitChanges(OutboxEvent event, EventPlan plan,
                                      List<Map<String, Object>> computedAdded,
                                      List<Map<String, Object>> computedRemoved,
                                      Map<String, Object> info) {
        long lastChangeId = -1L;
        for (ChangeUnit unit : plan.changeUnits()) {
            String globalId = unit.selfGlobalId();
            if (globalId == null || globalId.isBlank()) {
                continue;
            }
            String reason = unit.reason() == null || unit.reason().isBlank()
                    ? stringOf(info.get("reason"), null) : unit.reason();
            ChangeRecordRow change = new ChangeRecordRow(event.eventId(), globalId,
                    stringOf(info.get("changeKind"), event.changeKind()), "EVENT",
                    unit.before() == null ? Map.of() : unit.before(),
                    unit.after() == null ? Map.of() : unit.after(),
                    unit.diff() == null ? List.of() : unit.diff(),
                    edgesTouching(computedAdded, globalId),
                    edgesTouching(computedRemoved, globalId),
                    reason, stringOf(info.get("operator"), null), event.businessTime(),
                    stringOf(info.get("mappingVersion"), null));
            long changeId = store.appendChangeHistory(change);
            lastChangeId = changeId;
            store.appendImpact(changeId, globalId, "SELF_CHANGED", "本身属性或关系发生变化");
            // 刻意**不**把同批其它条目写成本条的 DERIVED_RELATED：
            // 每一条都有自己的 SELF_CHANGED 记录，若再互相标成「相关对象」，
            // 打开 LS-001 的近期变化仍会列出 LS-002 的那条记录（变化列表按 impact 命中），
            // 等于把这个缺陷换个形式带回来。相关对象只放真正由本条派生出来的对象。
            for (String related : plan.relatedGlobalIds()) {
                if (related != null && !related.isBlank() && !related.equals(globalId)) {
                    store.appendImpact(changeId, related, "DERIVED_RELATED", RELATED_BASIS);
                }
            }
        }
        store.bumpPlatformRevision();
        return lastChangeId;
    }

    /** 只保留端点等于该对象的边（一变多记录时，每条记录只描述自己的关系变化）。 */
    private static List<Map<String, Object>> edgesTouching(List<Map<String, Object>> edges,
                                                           String globalId) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (edges == null || edges.isEmpty()) {
            return result;
        }
        for (Map<String, Object> edge : edges) {
            if (globalId.equals(edge.get("fromGlobalId")) || globalId.equals(edge.get("toGlobalId"))) {
                result.add(edge);
            }
        }
        return result;
    }

    /**
     * 本事件真正改动关系的对象集合：**自身批次对象 + 墓碑对象**（按定义顺序去重）。
     *
     * <p>刻意不含 {@code refreshBatches}：邻居本次只刷新属性、关系不动，
     * 把它们纳入焦点只会把「未变化的关系」算进差集，产生假增删。</p>
     */
    private static Set<String> focusGlobalIds(EventPlan plan) {
        Set<String> result = new LinkedHashSet<>();
        for (SnapshotBatch batch : plan.batches()) {
            for (ProjectionRow row : batch.objects()) {
                if (row.globalId() != null && !row.globalId().isBlank()) {
                    result.add(row.globalId());
                }
            }
        }
        for (String globalId : plan.tombstoneGlobalIds()) {
            if (globalId != null && !globalId.isBlank()) {
                result.add(globalId);
            }
        }
        return result;
    }

    /**
     * 是否需要为对象追加版本行：对象不存在（首次落库）、曾被墓碑（复活）或属性确有变化时为 true。
     * 这样「邻居刷新」与重复事件都不会刷出无变化的版本行。
     */
    private boolean needsObjectVersion(ProjectionRow row) {
        ProjectionRow existing = store.object(row.globalId());
        if (existing == null || existing.deleted()) {
            return true;
        }
        return !sameAttributes(existing.attributes(), row.attributes());
    }

    private static boolean sameAttributes(Map<String, Object> left, Map<String, Object> right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())
                    || !sameValue(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** 值比较容忍类型漂移（来源 Integer vs JSON 读回的 Long/BigDecimal），避免误判为「变化」。 */
    private static boolean sameValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        if (left.equals(right)) {
            return true;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    /** 采集给定对象的当前有效关系（出边 + 入边），以 relationId 为键。 */
    private Map<String, RelationInstance> activeRelations(Set<String> globalIds) {
        Map<String, RelationInstance> result = new LinkedHashMap<>();
        for (String globalId : globalIds) {
            for (RelationInstance relation : store.activeRelationsFrom(globalId)) {
                result.put(relation.relationId(), relation);
            }
            for (RelationInstance relation : store.activeRelationsTo(globalId)) {
                result.put(relation.relationId(), relation);
            }
        }
        return result;
    }

    /**
     * 关系差集：{@code added=true} 取「后有前无」，否则取「前有后无」。
     * 关系实例本身不带标题，展示字段在此补齐（模型定义的 displayName + 投影里的对象标题）。
     */
    private List<Map<String, Object>> relationDiff(Map<String, RelationInstance> before,
                                                   Map<String, RelationInstance> after,
                                                   boolean added,
                                                   Map<String, String> titleCache) {
        Map<String, RelationInstance> source = added ? after : before;
        Map<String, RelationInstance> other = added ? before : after;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, RelationInstance> entry : source.entrySet()) {
            if (other.containsKey(entry.getKey())) {
                continue;
            }
            result.add(relationView(entry.getValue(), titleCache));
        }
        return result;
    }

    private Map<String, Object> relationView(RelationInstance relation, Map<String, String> titleCache) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("relationId", relation.relationId());
        view.put("relationType", relation.relationType());
        view.put("displayName", relationDisplayName(relation.relationType()));
        view.put("fromGlobalId", relation.fromGlobalId());
        view.put("fromTitle", objectTitle(relation.fromGlobalId(), titleCache));
        view.put("toGlobalId", relation.toGlobalId());
        view.put("toTitle", objectTitle(relation.toGlobalId(), titleCache));
        view.put("reason", relation.reason());
        return view;
    }

    /** 关系展示名一律取模型定义；定义缺失（如测试用空注册表）时回退关系类型标识，绝不抛异常。 */
    private String relationDisplayName(String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return "";
        }
        if (registry.hasRelation(relationType)) {
            String displayName = registry.relationOf(relationType).displayName;
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }
        return relationType;
    }

    /** 对象标题取当前投影；对象不存在或标题为空时回退 globalId，保证展示不出现空串。 */
    private String objectTitle(String globalId, Map<String, String> titleCache) {
        if (globalId == null || globalId.isBlank()) {
            return "";
        }
        String cached = titleCache.get(globalId);
        if (cached != null) {
            return cached;
        }
        ProjectionRow row = store.object(globalId);
        String title = (row == null || row.title() == null || row.title().isBlank())
                ? globalId : row.title();
        titleCache.put(globalId, title);
        return title;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? new LinkedHashMap<>((Map<String, Object>) value) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMap(Object value) {
        return value instanceof List ? new ArrayList<>((List<Map<String, Object>>) value) : List.of();
    }

    private static String stringOf(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}

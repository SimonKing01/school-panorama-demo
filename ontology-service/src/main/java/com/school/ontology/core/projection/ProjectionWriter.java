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
     * 事件投影落库（在 EventConsumer 的本体库单事务内调用）。
     * 依次写：对象 + 关系 + 对象版本 + 变更历史 + 影响 + 来源引用 + 平台版本。
     */
    public long applyEvent(OutboxEvent event, EventPlan plan) {
        long revision = event.revision();
        for (SnapshotBatch batch : plan.batches()) {
            applySnapshot(batch, revision);
            for (ProjectionRow row : batch.objects()) {
                store.appendObjectVersion(row.globalId(), revision,
                        new LinkedHashMap<>(row.attributes()), event.eventId());
            }
        }
        for (String globalId : plan.tombstoneGlobalIds()) {
            tombstone(globalId, revision);
        }
        if (!plan.demoTimeUpdate().isEmpty()) {
            store.updateDataSource(registry.sourceId(), plan.demoTimeUpdate());
        }

        Map<String, Object> info = plan.changeInfo();
        String primaryGid = plan.selfGlobalIds().isEmpty()
                ? (plan.tombstoneGlobalIds().isEmpty() ? "" : plan.tombstoneGlobalIds().get(0))
                : plan.selfGlobalIds().get(0);
        if (primaryGid.isBlank()) {
            // CLOCK 等只改演示时间的事件不产生对象属性变化，不写业务变更历史。
            store.bumpPlatformRevision();
            return -1L;
        }
        ChangeRecordRow change = new ChangeRecordRow(event.eventId(), primaryGid,
                stringOf(info.get("changeKind"), event.changeKind()),
                "EVENT", mapOf(info.get("before")), mapOf(info.get("after")),
                listOfMap(info.get("diff")), listOfMap(info.get("relationsAdded")),
                listOfMap(info.get("relationsRemoved")),
                stringOf(info.get("reason"), null), stringOf(info.get("operator"), null),
                event.businessTime(), stringOf(info.get("mappingVersion"), null));
        long changeId = store.appendChangeHistory(change);
        for (String gid : plan.selfGlobalIds()) {
            store.appendImpact(changeId, gid, "SELF_CHANGED", "本身属性或关系发生变化");
        }
        for (String gid : plan.relatedGlobalIds()) {
            store.appendImpact(changeId, gid, "DERIVED_RELATED",
                    "仅为按规则计算出的相关对象，业务状态未改变");
        }
        store.bumpPlatformRevision();
        return changeId;
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

package com.school.ontology.core.sync;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对账（D7）：通过只读 SourceAdapter **直读业务库当前状态**，不依赖事件，
 * 因此能发现「绕过业务服务直接改库」的差异。
 *
 * <p>修复只改当前投影，并追加一条 origin=RECONCILE 的变更历史：
 * operator / businessTime 均为 null，reason 明确写「非业务操作」，
 * 不伪造操作人、发生时间与中间过程。对账可重复执行，第二次无差异。</p>
 */
@Component
public class ReconcileService {

    private final SnapshotSource source;
    private final ProjectionStore store;
    private final ProjectionWriter writer;
    private final ModelDefinitionLoader loader;
    private final OntologyRepository repository;

    public ReconcileService(SnapshotSource source, ProjectionStore store, ProjectionWriter writer,
                            ModelDefinitionLoader loader, OntologyRepository repository) {
        this.source = source;
        this.store = store;
        this.writer = writer;
        this.loader = loader;
        this.repository = repository;
    }

    public long reconcile(boolean dryRun) {
        if (!source.healthy()) {
            throw ApiException.badRequest("来源业务库不可用，无法执行对账");
        }
        long watermark = source.watermark();
        SnapshotBatch expected = SnapshotBatch.empty();
        for (SnapshotBatch batch : source.snapshot(watermark).values()) {
            expected.merge(batch);
        }

        Map<String, ProjectionRow> expectedObjects = new LinkedHashMap<>();
        for (ProjectionRow row : expected.objects()) {
            expectedObjects.put(row.globalId(), row);
        }
        Map<String, ProjectionRow> actualObjects = new LinkedHashMap<>();
        for (ProjectionRow row : store.objects()) {
            actualObjects.put(row.globalId(), row);
        }
        Set<String> expectedRelations = new LinkedHashSet<>();
        for (RelationInstance relation : expected.relations()) {
            expectedRelations.add(relation.relationId());
        }
        Set<String> actualRelations = new LinkedHashSet<>();
        for (RelationInstance relation : store.relations()) {
            if (relation.active()) {
                actualRelations.add(relation.relationId());
            }
        }

        List<Map<String, Object>> diffs = new ArrayList<>();
        int missing = 0;
        int orphan = 0;
        int fieldDiff = 0;
        int relationDiff = 0;
        int fixed = 0;

        for (Map.Entry<String, ProjectionRow> entry : expectedObjects.entrySet()) {
            String gid = entry.getKey();
            ProjectionRow want = entry.getValue();
            ProjectionRow have = actualObjects.get(gid);
            if (have == null || have.deleted()) {
                missing++;
                diffs.add(diff("MISSING", gid, "source", "missing", true,
                        "来源存在但投影缺失；补对象与关系"));
                if (!dryRun) {
                    writer.applySnapshot(singleObjectBatch(want, expected), watermark);
                    fixed++;
                }
            } else if (!want.attributes().equals(have.attributes())) {
                fieldDiff++;
                diffs.add(diff("FIELD_DIFF", gid, "attributes", "attributes", true,
                        "绕过业务服务直改库产生，无对应事件；仅修复当前投影，未伪造历史"));
                if (!dryRun && writer.compareAndUpsert(gid,
                        Math.max(watermark, have.appliedRevision()), want.attributes())) {
                    fixed++;
                }
            }
        }
        for (Map.Entry<String, ProjectionRow> entry : actualObjects.entrySet()) {
            if (!entry.getValue().deleted() && !expectedObjects.containsKey(entry.getKey())) {
                orphan++;
                diffs.add(diff("ORPHAN", entry.getKey(), "source", "missing", true,
                        "投影存在但来源已无；墓碑化并失效关系"));
                if (!dryRun) {
                    writer.tombstone(entry.getKey(), watermark);
                    fixed++;
                }
            }
        }
        for (String relationId : expectedRelations) {
            if (!actualRelations.contains(relationId)) {
                relationDiff++;
                diffs.add(diff("RELATION_DIFF", null, "relationId", relationId, true,
                        "关系端点或有效时间与来源不一致；按稳定 ID 重建该关系"));
            }
        }
        for (String relationId : actualRelations) {
            if (!expectedRelations.contains(relationId)) {
                relationDiff++;
                diffs.add(diff("RELATION_DIFF", null, "relationId", relationId, true,
                        "投影中存在来源已无的有效关系；失效该关系"));
            }
        }
        if (relationDiff > 0 && !dryRun) {
            writer.applySnapshot(expected, watermark);
            fixed += relationDiff;
        }

        if (fixed > 0 && !dryRun) {
            String primaryGid = expectedObjects.isEmpty()
                    ? (actualObjects.isEmpty() ? "" : actualObjects.keySet().iterator().next())
                    : expectedObjects.keySet().iterator().next();
            store.appendChangeHistory(new ChangeRecordRow(null, primaryGid,
                    "RECONCILE_FIX", "RECONCILE", Map.of(), Map.of(), diffs,
                    List.of(), List.of(), "对账修复（非业务操作）", null, null,
                    loader.mappingVersion().mappingVersion));
            store.bumpPlatformRevision();
        }

        Map<String, Object> summary = Resp.map(
                "checkedObjects", expectedObjects.size(),
                "checkedRelations", expectedRelations.size(),
                "missing", missing, "orphan", orphan,
                "fieldDiff", fieldDiff, "relationDiff", relationDiff,
                "fixed", fixed, "dryRun", dryRun,
                "watermark", watermark);
        String status = dryRun ? "DRY_RUN" : "SUCCESS";
        long runId = store.appendReconcileRun(status, diffs, summary);
        store.updateReconcileRun(runId, status, diffs, summary);
        return runId;
    }

    public Map<String, Object> runs(int page, int size) {
        int offset = Math.max(page - 1, 0) * size;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : repository.reconcileRuns(size, offset)) {
            items.add(Resp.map(
                    "runId", OntologyRepository.lng(row.get("run_id")),
                    "status", OntologyRepository.str(row.get("status")),
                    "diffs", repository.parseList(row.get("diff_json")),
                    "summary", repository.parseMap(row.get("summary_json")),
                    "startedAt", Resp.ts(OntologyRepository.dateTime(row.get("started_at"))),
                    "finishedAt", Resp.ts(OntologyRepository.dateTime(row.get("finished_at")))));
        }
        return Resp.map("page", page, "size", size, "total", items.size(), "items", items);
    }

    private static SnapshotBatch singleObjectBatch(ProjectionRow row, SnapshotBatch expected) {
        List<ProjectionRow> objects = new ArrayList<>();
        objects.add(row);
        List<RelationInstance> relations = new ArrayList<>();
        for (RelationInstance relation : expected.relations()) {
            if (relation.fromGlobalId().equals(row.globalId())) {
                relations.add(relation);
            }
        }
        List<com.school.ontology.core.projection.SourceRefRow> refs = new ArrayList<>();
        for (com.school.ontology.core.projection.SourceRefRow ref : expected.sourceRefs()) {
            if (ref.globalId().equals(row.globalId())) {
                refs.add(ref);
            }
        }
        return new SnapshotBatch(objects, relations, refs);
    }

    private static Map<String, Object> diff(String kind, String globalId, String field,
                                            Object value, boolean fixed, String note) {
        return Resp.map("kind", kind, "globalId", globalId, "field", field,
                "value", value, "fixed", fixed, "note", note);
    }
}

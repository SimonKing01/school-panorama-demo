package com.school.ontology.core.sync;

import com.school.ontology.common.ApiException;
import com.school.ontology.common.Resp;
import com.school.ontology.core.model.AttributeDef;
import com.school.ontology.core.model.DerivedPropertyDef;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ObjectTypeDef;
import com.school.ontology.core.projection.ChangeRecordRow;
import com.school.ontology.core.projection.ProjectionRow;
import com.school.ontology.core.projection.ProjectionStore;
import com.school.ontology.core.projection.ProjectionWriter;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.spi.RelationInstance;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                diffs.add(diff("MISSING", gid, want.title(), "source", "missing", true,
                        "来源存在但投影缺失；补对象与关系"));
                if (!dryRun) {
                    writer.applySnapshot(singleObjectBatch(want, expected), watermark);
                    fixed++;
                }
            } else {
                // 差异判定不能用 attributes() 直接 equals（缺陷修复，2026-11-17）：
                // attributes() 是「渲染视图」，混入了 derivedProperties 计算出来的派生属性，
                // 且 JSONB 反序列化出的数值（Long 8）与内存计算值（Integer 8）类型不同。
                // 直接 equals 会把「8 与 8」判成差异，导致对账出现大量假阳性（实测 56 条里 54 条
                // before==after），对账永远对不平、变更历史被噪音淹没。
                // 改为只比来源映射出来的属性，并按数值/字符串语义归一化比较。
                List<Map<String, Object>> entries = fieldDiffs(want, have);
                boolean attributesDiffer = !want.attributes().equals(have.attributes());
                if (!entries.isEmpty()) {
                    fieldDiff++;
                    diffs.addAll(entries);
                }
                // 即使只剩派生属性/类型差异（entries 为空但顶层不等），也照旧修正投影，
                // 只是不写进变更历史 —— 没有业务变化就不该产生变更记录。
                if (!dryRun && attributesDiffer && writer.compareAndUpsert(gid,
                        Math.max(watermark, have.appliedRevision()), want.attributes())) {
                    if (!entries.isEmpty()) {
                        fixed++;
                    }
                }
            }
        }
        for (Map.Entry<String, ProjectionRow> entry : actualObjects.entrySet()) {
            if (!entry.getValue().deleted() && !expectedObjects.containsKey(entry.getKey())) {
                orphan++;
                diffs.add(diff("ORPHAN", entry.getKey(), entry.getValue().title(),
                        "source", "missing", true,
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
                diffs.add(diff("RELATION_DIFF", null, null, "relationId", relationId, true,
                        "关系端点或有效时间与来源不一致；按稳定 ID 重建该关系"));
            }
        }
        for (String relationId : actualRelations) {
            if (!expectedRelations.contains(relationId)) {
                relationDiff++;
                diffs.add(diff("RELATION_DIFF", null, null, "relationId", relationId, true,
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

    private static Map<String, Object> diff(String kind, String globalId, String title, String field,
                                            Object value, boolean fixed, String note) {
        return Resp.map("kind", kind, "globalId", globalId, "title", title, "field", field,
                "value", value, "fixed", fixed, "note", note);
    }

    /** 单个对象的字段级差异最多展示 5 条，超出部分收敛为一条汇总，防止单条记录数百行。 */
    private static final int FIELD_DIFF_LIMIT = 5;

    /**
     * 逐字段比对 want/have 的属性快照，产出真实字段级 FIELD_DIFF
     * （field/label/before/after 齐全，label 取模型定义的 AttributeDef.displayName）。
     *
     * <p>历史教训：这里曾把整段 attributes 压缩成一条 {@code field="attributes"} 的占位记录，
     * 前端按 label/before/after 渲染时三字段全空。任何取 label 失败、类型未注册等异常
     * 都只降级（回退字段名或回退单条占位记录），绝不允许让对账整体失败。</p>
     */
    private List<Map<String, Object>> fieldDiffs(ProjectionRow want, ProjectionRow have) {
        List<Map<String, Object>> entries = new ArrayList<>();
        try {
            Map<String, Object> target = want.attributes() == null ? Map.of() : want.attributes();
            Map<String, Object> current = have.attributes() == null ? Map.of() : have.attributes();
            // 排除派生属性：它们由规则运行时算出、不来自源表，比进来只会产生「8 与 8」的噪音。
            // 定义里没声明的字段仍然参与比对，不静默忽略真实差异。
            Set<String> derived = derivedAttributeNames(want.objectType());
            Set<String> fields = new LinkedHashSet<>();
            for (String key : target.keySet()) {
                if (!derived.contains(key)) {
                    fields.add(key);
                }
            }
            for (String key : current.keySet()) {
                if (!derived.contains(key)) {
                    fields.add(key);
                }
            }
            String gid = want.globalId();
            String title = want.title();
            String labelNote = "绕过业务服务直改库产生，无对应事件；仅修复当前投影，未伪造历史";
            int truncated = 0;
            for (String field : fields) {
                Object before = current.get(field);
                Object after = target.get(field);
                if (sameValue(before, after)) {
                    continue;
                }
                if (entries.size() >= FIELD_DIFF_LIMIT) {
                    truncated++;
                    continue;
                }
                entries.add(Resp.map("kind", "FIELD_DIFF", "globalId", gid, "title", title,
                        "field", field, "label", attributeLabel(want.objectType(), field),
                        "before", before, "after", after, "fixed", true, "note", labelNote));
            }
            if (truncated > 0) {
                entries.add(Resp.map("kind", "FIELD_DIFF_SUMMARY", "globalId", gid, "title", title,
                        "field", "attributes", "fixed", true,
                        "note", "另有 " + truncated + " 个字段变化（超出单条记录展示上限，已截断）"));
            }
        } catch (Exception e) {
            // 字段级明细构造失败时降级为一条对象级占位记录，对账修复本身不受影响。
            entries.add(diff("FIELD_DIFF", want.globalId(), want.title(), "attributes",
                    "attributes", true,
                    "属性快照与来源不一致，已修正（字段级明细生成失败："
                            + e.getClass().getSimpleName() + "）"));
        }
        return entries;
    }

    /** 字段显示名：来自模型定义 AttributeDef.displayName；定义缺失或类型未注册时回退字段名本身。 */
    /**
     * 派生属性的名字集合：取 object-types 里 {@code derivedProperties} 声明的 name。
     *
     * <p>{@code ProjectionRow.attributes()} 是给渲染用的完整视图，把派生属性也混在里面；
     * 对账要回答的是「投影是否忠实反映了来源表」，而派生属性（classCount / studentCount 等）
     * 由规则运行时算出、不来自源表，纳入比对只会产生「8 与 8」这类假差异。</p>
     *
     * <p>这里用「黑名单排除派生属性」而不是「白名单只留源属性」：定义里没声明的字段
     * （历史遗留、迁移期字段）仍应参与比对，不能因为查不到定义就静默忽略真实差异。</p>
     *
     * @return 取不到类型定义时返回空集，此时比对全部字段（由 {@link #sameValue} 兜底）
     */
    private Set<String> derivedAttributeNames(String objectType) {
        try {
            ObjectTypeDef def = loader.registry().typeOf(objectType);
            if (def == null || def.derivedProperties == null) {
                return Set.of();
            }
            Set<String> names = new LinkedHashSet<>();
            for (DerivedPropertyDef property : def.derivedProperties) {
                if (property != null && property.name != null && !property.name.isBlank()) {
                    names.add(property.name);
                }
            }
            return names;
        } catch (RuntimeException e) {
            // 类型未注册 / 定义读取失败：退化为比全部字段，绝不让对账整体失败。
            return Set.of();
        }
    }

    /**
     * 值等价判定（缺陷修复，2026-11-17）。
     *
     * <p>投影从 JSONB 反序列化回来的数值是 {@code Long} / {@code BigDecimal}，
     * 而按来源重新计算得到的是 {@code Integer}，{@code Objects.equals} 判为不等，
     * 但两边序列化成 JSON 后完全一样 —— 直接比对会让对账永远对不平。</p>
     *
     * <p>比较顺序：严格相等 → 数值按 {@link BigDecimal#compareTo} 比大小 →
     * 其余按字符串字面比较（覆盖 {@code LocalTime} / {@code LocalDate} 与字符串互转）。</p>
     */
    private static boolean sameValue(Object before, Object after) {
        if (Objects.equals(before, after)) {
            return true;
        }
        if (before == null || after == null) {
            return false;
        }
        if (before instanceof Number left && after instanceof Number right) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException e) {
                return String.valueOf(left).equals(String.valueOf(right));
            }
        }
        return String.valueOf(before).equals(String.valueOf(after));
    }

    private String attributeLabel(String objectType, String field) {
        try {
            AttributeDef def = loader.registry().typeOf(objectType).attribute(field);
            if (def != null && def.displayName != null && !def.displayName.isBlank()) {
                return def.displayName;
            }
        } catch (RuntimeException ignored) {
            // 类型未注册 / 定义读取失败：回退字段名，绝不向上抛。
        }
        return field;
    }
}

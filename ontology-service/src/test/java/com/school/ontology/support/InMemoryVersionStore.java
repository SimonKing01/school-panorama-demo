package com.school.ontology.support;

import com.school.ontology.repository.OntologyRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 版本表的内存替身（P6 缺陷回归用）。
 *
 * <p>为什么不用纯 Mockito 打桩：本缺陷的核心恰恰是「UPSERT 的冲突分支怎么走」——
 * 同一版本行被保存、激活、恢复基线反复更新，只有在有状态的表上才能验证
 * 「operator 不被 system 覆盖」「同一草稿重复保存仍只有一行」。
 * 这里用 {@link Mockito#mock} 的 {@code thenAnswer} 把语义最相关的几个方法
 * 委托给下面这份内存表，SQL 语义与 {@link OntologyRepository} 中同名方法保持一致。</p>
 *
 * <p>注意：这只是测试替身，所有断言都针对「表里最终是什么样」，不针对调用次序。</p>
 */
public final class InMemoryVersionStore {

    private static final String SYSTEM = "system";

    /** key = sourceId + "|" + mappingVersion。 */
    private final Map<String, Map<String, Object>> versions = new LinkedHashMap<>();
    /** key = def_key。 */
    private final Map<String, Map<String, Object>> defs = new LinkedHashMap<>();
    private long sequence = 0L;

    /** 把 Mockito 替身绑定到本内存表，返回可直接注入被测类的 mock。 */
    public OntologyRepository bind() {
        OntologyRepository repository = mock(OntologyRepository.class);

        doAnswer(invocation -> {
            upsertVersion(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(5), invocation.getArgument(6));
            return null;
        }).when(repository).upsertMappingVersion(anyString(), anyString(), anyString(),
                anyString(), any(), any(), any());

        doAnswer(invocation -> {
            markPending(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3),
                    invocation.getArgument(4), invocation.getArgument(5));
            return null;
        }).when(repository).markPendingMappingVersion(anyString(), anyString(), anyString(),
                any(), any(), any());

        doAnswer(invocation -> {
            upsertDef(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6));
            return null;
        }).when(repository).upsertModelDef(anyString(), anyString(), any(), anyString(),
                any(), any(), any());

        doAnswer(invocation -> {
            insertDef(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6));
            return null;
        }).when(repository).insertModelDefVersion(anyString(), anyString(), any(),
                anyString(), any(), any(), any());

        when(repository.mappingVersionRow(anyString(), anyString()))
                .thenAnswer(invocation -> copy(row(invocation.getArgument(0), invocation.getArgument(1))));
        when(repository.activeMappingVersion(anyString()))
                .thenAnswer(invocation -> latest(invocation.getArgument(0), "ACTIVE"));
        when(repository.latestPendingMappingVersion(anyString()))
                .thenAnswer(invocation -> latest(invocation.getArgument(0), "PENDING"));
        when(repository.mappingVersions(anyString())).thenAnswer(invocation -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> value : versions.values()) {
                if (invocation.getArgument(0).equals(value.get("source_id"))) {
                    rows.add(copy(value));
                }
            }
            return rows;
        });

        doAnswer(invocation -> supersede(invocation.getArgument(0), invocation.getArgument(1), "ACTIVE"))
                .when(repository).supersedeOtherActive(anyString(), any());
        doAnswer(invocation -> supersede(invocation.getArgument(0), invocation.getArgument(1), "PENDING"))
                .when(repository).supersedeOtherPending(anyString(), any());
        when(repository.hasRunningTask(anyString())).thenReturn(false);
        return repository;
    }

    // ── 断言用的读取口 ──────────────────────────────────────────────────────

    /** 版本表全部行（按写入顺序）。 */
    public List<Map<String, Object>> allVersions() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> value : versions.values()) {
            rows.add(copy(value));
        }
        return rows;
    }

    /** 指定版本行；不存在返回空 Map。 */
    public Map<String, Object> version(String sourceId, String mappingVersion) {
        return copy(row(sourceId, mappingVersion));
    }

    /** 指定状态的版本行。 */
    public List<Map<String, Object>> versionsWithStatus(String status) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> value : versions.values()) {
            if (status.equals(value.get("status"))) {
                rows.add(copy(value));
            }
        }
        return rows;
    }

    /** 模型定义内容行（含 {@code <type>@<version>} 形式的历史快照）。 */
    public Map<String, Object> modelDef(String defKey) {
        return copy(defs.get(defKey));
    }

    public List<String> modelDefKeys() {
        return new ArrayList<>(defs.keySet());
    }

    // ── 与 SQL 等价的内部实现 ──────────────────────────────────────────────

    private void upsertVersion(String sourceId, String mappingVersion, String definitionHash,
                               String status, String operator, String note, String definitionSource) {
        Map<String, Object> row = row(sourceId, mappingVersion);
        if (row == null) {
            row = newRow(sourceId, mappingVersion);
        }
        // 与 SQL 一致：行上已有人写的 operator 时，operator/note 原样保留。
        if (!isHuman(row.get("operator"))) {
            row.put("operator", operator == null ? row.get("operator") : operator);
            row.put("note", note == null ? row.get("note") : note);
        }
        row.put("definition_hash", definitionHash);
        row.put("status", status);
        if (definitionSource != null) {
            row.put("definition_source", definitionSource);
        }
        touch(row);
    }

    private void markPending(String sourceId, String mappingVersion, String definitionHash,
                             String operator, String note, String definitionSource) {
        Map<String, Object> row = row(sourceId, mappingVersion);
        if (row == null) {
            row = newRow(sourceId, mappingVersion);
        }
        // 与 SQL 一致：目标版本正好是当前 ACTIVE 时保持 ACTIVE，避免出现「没有生效版本」的空窗。
        row.put("status", "ACTIVE".equals(row.get("status")) ? "ACTIVE" : "PENDING");
        row.put("definition_hash", definitionHash);
        row.put("operator", operator == null ? row.get("operator") : operator);
        row.put("note", note == null ? row.get("note") : note);
        if (definitionSource != null) {
            row.put("definition_source", definitionSource);
        }
        touch(row);
    }

    private int supersede(String sourceId, String keepVersion, String fromStatus) {
        int updated = 0;
        String keep = keepVersion == null ? "" : keepVersion;
        for (Map<String, Object> value : versions.values()) {
            if (!sourceId.equals(value.get("source_id"))) {
                continue;
            }
            if (!fromStatus.equals(value.get("status"))) {
                continue;
            }
            if (keep.equals(value.get("mapping_version"))) {
                continue;
            }
            value.put("status", "SUPERSEDED");
            updated++;
        }
        return updated;
    }

    private void upsertDef(String defKey, String defType, Object content, String mappingVersion,
                           String operator, String note, String definitionSource) {
        Map<String, Object> row = defs.get(defKey);
        if (row == null) {
            row = newDefRow(defKey, defType);
        }
        row.put("content", content);
        row.put("mapping_version", mappingVersion);
        if (!isHuman(row.get("operator"))) {
            row.put("operator", operator == null ? row.get("operator") : operator);
            row.put("note", note == null ? row.get("note") : note);
        }
        if (definitionSource != null) {
            row.put("definition_source", definitionSource);
        }
        row.put("updated_at", LocalDateTime.now());
    }

    private void insertDef(String defKey, String defType, Object content, String mappingVersion,
                           String operator, String note, String definitionSource) {
        // 与 SQL 的 ON CONFLICT (def_key) DO NOTHING 一致：一版一行，只插不改。
        if (defs.containsKey(defKey)) {
            return;
        }
        Map<String, Object> row = newDefRow(defKey, defType);
        row.put("content", content);
        row.put("mapping_version", mappingVersion);
        row.put("operator", operator);
        row.put("note", note);
        row.put("definition_source", definitionSource);
        defs.put(defKey, row);
    }

    private Map<String, Object> newRow(String sourceId, String mappingVersion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("source_id", sourceId);
        row.put("mapping_version", mappingVersion);
        row.put("status", "PENDING");
        versions.put(key(sourceId, mappingVersion), row);
        return row;
    }

    private Map<String, Object> newDefRow(String defKey, String defType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("def_key", defKey);
        row.put("def_type", defType);
        defs.put(defKey, row);
        return row;
    }

    private void touch(Map<String, Object> row) {
        sequence++;
        row.put("activated_at", LocalDateTime.now());
        row.put("_seq", sequence);
    }

    private Map<String, Object> latest(String sourceId, String status) {
        Map<String, Object> found = null;
        long bestSeq = -1L;
        for (Map<String, Object> value : versions.values()) {
            if (!sourceId.equals(value.get("source_id")) || !status.equals(value.get("status"))) {
                continue;
            }
            long seq = value.get("_seq") == null ? 0L : ((Number) value.get("_seq")).longValue();
            if (seq >= bestSeq) {
                bestSeq = seq;
                found = value;
            }
        }
        return found == null ? Map.of() : copy(found);
    }

    private Map<String, Object> row(String sourceId, String mappingVersion) {
        return versions.get(key(sourceId, mappingVersion));
    }

    private static String key(String sourceId, String mappingVersion) {
        return sourceId + "|" + mappingVersion;
    }

    private static boolean isHuman(Object operator) {
        return operator != null && !String.valueOf(operator).isBlank()
                && !SYSTEM.equals(String.valueOf(operator));
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source == null ? Map.of() : new LinkedHashMap<>(source);
    }
}

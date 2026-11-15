package com.school.ontology.adapter.school;

import com.school.ontology.common.Resp;
import com.school.ontology.core.model.GlobalIds;
import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelDocument;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.model.ObjectMappingDef;
import com.school.ontology.core.spi.BusinessTime;
import com.school.ontology.core.spi.LookupResolver;
import com.school.ontology.core.spi.SourceAdapter;
import com.school.ontology.core.spi.SourceQuery;
import com.school.ontology.core.spi.SourceRecord;
import com.school.ontology.repository.OntologyRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学校业务库的只读 SourceAdapter（架构 §11-A，主理人已拍板允许只读直连）。
 *
 * <p>边界：独立 Hikari 池 + 连接级 {@code setReadOnly(true)} + 本类只有 SELECT。
 * 底座绝不写业务库；部署时应把 {@code SOURCE_DB_USER} 换成只读账号。
 * 用于全量导入 / 对账 / 重建 / 健康探测，不构成业务事务依赖。</p>
 *
 * <p>本类是学校领域唯一知道来源表名的地方，但表名/主键/列名全部来自
 * school-source.yaml，且经白名单校验，通用内核不含任何业务类型分支。</p>
 */
@Component
public class SchoolJdbcSourceAdapter implements SourceAdapter {

    private final JdbcTemplate jdbc;
    private final ModelRegistry registry;
    private final ModelDocument.DataSourceDef dataSourceDef;
    private final LookupResolver lookups = new JdbcLookupResolver();

    public SchoolJdbcSourceAdapter(@Qualifier("sourceJdbc") JdbcTemplate jdbc,
                                   ModelDefinitionLoader loader,
                                   ModelRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.dataSourceDef = loader.sourceDocument().dataSource;
    }

    @Override
    public String sourceId() {
        return dataSourceDef.id;
    }

    @Override
    public boolean healthCheck() {
        try {
            Integer value = jdbc.queryForObject(dataSourceDef.healthCheckSql, Integer.class);
            return value != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long currentWatermark() {
        ModelDocument.WatermarkDef watermark = dataSourceDef.watermark;
        String sql = "SELECT " + OntologyRepository.safeIdentifier(watermark.column)
                + " FROM " + OntologyRepository.safeIdentifier(watermark.table) + " WHERE id = 1";
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public List<SourceRecord> scan(SourceQuery query) {
        ObjectMappingDef mapping = registry.mappingOf(query.objectType());
        String table = OntologyRepository.safeIdentifier(mapping.sourceTable);
        String pkColumn = OntologyRepository.safeIdentifier(mapping.pk);
        List<Map<String, Object>> rows;
        try {
            if (query.sourcePk() != null) {
                rows = jdbc.queryForList("SELECT * FROM " + table
                        + " WHERE " + pkColumn + "::text = ?", String.valueOf(query.sourcePk()));
            } else {
                rows = jdbc.queryForList("SELECT * FROM " + table
                        + " ORDER BY " + pkColumn + " LIMIT ? OFFSET ?",
                        query.size(), (query.page() - 1) * query.size());
            }
        } catch (Exception e) {
            // 来源不可用时不抛到业务侧；导入任务由调用方根据 healthCheck 判定。
            return List.of();
        }
        List<SourceRecord> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                normalized.put(entry.getKey(), Resp.normalize(entry.getValue()));
            }
            String sourcePk = String.valueOf(row.get(pkColumn));
            result.add(new SourceRecord(table, sourcePk, normalized,
                    GlobalIds.recordHash(normalized)));
        }
        return result;
    }

    @Override
    public BusinessTime currentBusinessTime() {
        try {
            Map<String, Object> row = jdbc.queryForMap(dataSourceDef.businessTimeSql);
            return new BusinessTime(
                    OntologyRepository.dateTime(row.get("demo_at")),
                    OntologyRepository.bool(row.get("running")),
                    OntologyRepository.dateTime(row.get("anchor_real_at")));
        } catch (Exception e) {
            return new BusinessTime(LocalDateTime.now(), false, null);
        }
    }

    @Override
    public LookupResolver lookups() {
        return lookups;
    }

    /** 通用只读联表实现：标识符来自可信 YAML 且经白名单校验，全部语句只有 SELECT。 */
    private final class JdbcLookupResolver implements LookupResolver {

        @Override
        public String name(String table, String nameColumn, Object key) {
            if (key == null) {
                return null;
            }
            String sql = "SELECT " + OntologyRepository.safeIdentifier(nameColumn)
                    + " FROM " + OntologyRepository.safeIdentifier(table)
                    + " WHERE id::text = ?";
            return firstText(sql, String.valueOf(key));
        }

        @Override
        public String foreignKey(String table, String joinColumn, Object key) {
            if (key == null) {
                return null;
            }
            String sql = "SELECT " + OntologyRepository.safeIdentifier(joinColumn)
                    + " FROM " + OntologyRepository.safeIdentifier(table)
                    + " WHERE id::text = ?";
            return firstText(sql, String.valueOf(key));
        }

        @Override
        public List<String> joinKeys(String table, String filterColumn, Object filterValue,
                                     String selectColumn) {
            if (filterValue == null) {
                return List.of();
            }
            String sql = "SELECT DISTINCT " + OntologyRepository.safeIdentifier(selectColumn)
                    + " FROM " + OntologyRepository.safeIdentifier(table)
                    + " WHERE " + OntologyRepository.safeIdentifier(filterColumn) + "::text = ?";
            try {
                List<String> result = new ArrayList<>();
                for (Object value : jdbc.queryForList(sql, String.class, String.valueOf(filterValue))) {
                    if (value != null) {
                        result.add(String.valueOf(value));
                    }
                }
                return result;
            } catch (Exception e) {
                return List.of();
            }
        }

        @Override
        public long count(String table, String filterColumn, Object filterValue) {
            if (filterValue == null) {
                return 0L;
            }
            String sql = "SELECT count(*) FROM " + OntologyRepository.safeIdentifier(table)
                    + " WHERE " + OntologyRepository.safeIdentifier(filterColumn) + "::text = ?";
            try {
                Long value = jdbc.queryForObject(sql, Long.class, String.valueOf(filterValue));
                return value == null ? 0L : value;
            } catch (Exception e) {
                return 0L;
            }
        }

        @Override
        public Map<String, Object> row(String table, Object pk) {
            if (pk == null) {
                return Map.of();
            }
            String sql = "SELECT * FROM " + OntologyRepository.safeIdentifier(table)
                    + " WHERE id::text = ?";
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(sql, String.valueOf(pk));
                if (rows.isEmpty()) {
                    return Map.of();
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : rows.get(0).entrySet()) {
                    normalized.put(entry.getKey(), Resp.normalize(entry.getValue()));
                }
                return normalized;
            } catch (Exception e) {
                return Map.of();
            }
        }

        private String firstText(String sql, String key) {
            try {
                List<String> rows = jdbc.queryForList(sql, String.class, key);
                return rows.isEmpty() ? null : rows.get(0);
            } catch (Exception e) {
                return null;
            }
        }
    }
}

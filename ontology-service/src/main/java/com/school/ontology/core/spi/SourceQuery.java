package com.school.ontology.core.spi;

/** 全量快照查询：按对象类型分页，或按来源主键精确取一条。 */
public record SourceQuery(String objectType, int page, int size, String sourcePk) {

    public static SourceQuery page(String objectType, int page, int size) {
        return new SourceQuery(objectType, Math.max(page, 1), Math.max(size, 1), null);
    }

    public static SourceQuery byPk(String objectType, String sourcePk) {
        return new SourceQuery(objectType, 1, 1, sourcePk);
    }
}

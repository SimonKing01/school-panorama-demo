package com.school.ontology.core.spi;

import java.util.List;
import java.util.Map;

/**
 * 通用只读联表端口：把「按外键查名称 / 查上级外键 / 按条件取关联键 / 计数」抽象成
 * 与业务无关的 SQL 能力，标识符全部来自可信 YAML 且由适配层做白名单校验。
 */
public interface LookupResolver {

    String name(String table, String nameColumn, Object key);

    String foreignKey(String table, String joinColumn, Object key);

    List<String> joinKeys(String table, String filterColumn, Object filterValue, String selectColumn);

    long count(String table, String filterColumn, Object filterValue);

    Map<String, Object> row(String table, Object pk);
}

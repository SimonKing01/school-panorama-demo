package com.school.ontology.core.spi;

import java.util.List;

/**
 * 接入一个新数据源的唯一必改接口（其余靠 YAML 定义）。
 * 实现只允许只读访问业务库：全量导入 / 对账 / 重建 / 健康探测 / 快照水位。
 */
public interface SourceAdapter {

    /** 稳定源标识，参与全局 ID，跨源隔离的根本。 */
    String sourceId();

    /** 来源可用性探测（E6「来源不可用」）。 */
    boolean healthCheck();

    /** 读业务库 system_revision.revision，作为全量导入的快照水位。 */
    long currentWatermark();

    /** 全量快照：按对象类型分页读来源表（只读事务 / MVCC）。 */
    List<SourceRecord> scan(SourceQuery query);

    /** 演示业务时间（demo_at/running/anchor），供动态计算与新鲜度展示。 */
    BusinessTime currentBusinessTime();

    /** 通用联表/名称/计数查询端口（只读），供字段转换、联表关系与派生属性使用。 */
    LookupResolver lookups();
}

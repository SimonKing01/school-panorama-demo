package com.school.ontology.core.sync;

import com.school.ontology.core.projection.SnapshotBatch;

import java.util.LinkedHashMap;

/**
 * 只读来源快照端口：把「取水位 / 探测健康 / 按类型产出投影批次」抽象出来，
 * 使导入、对账、重建与单元测试可以共享同一套「稳定 ID + 定义驱动」逻辑。
 */
public interface SnapshotSource {

    long watermark();

    boolean healthy();

    /** 对象类型 → 该类型的完整投影批次（按 YAML 定义顺序）。 */
    LinkedHashMap<String, SnapshotBatch> snapshot(long revision);
}

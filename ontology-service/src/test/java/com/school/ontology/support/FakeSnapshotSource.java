package com.school.ontology.support;

import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.sync.SnapshotSource;

import java.util.LinkedHashMap;

/** 可编排的假来源：模拟「导入期间业务写入导致水位从 W0 涨到 W1」等场景。 */
public class FakeSnapshotSource implements SnapshotSource {

    private final LinkedHashMap<String, SnapshotBatch> batches;
    private long firstWatermark;
    private long endWatermark;
    private boolean healthy = true;
    private int watermarkCalls = 0;

    public FakeSnapshotSource(LinkedHashMap<String, SnapshotBatch> batches,
                              long firstWatermark, long endWatermark) {
        this.batches = batches;
        this.firstWatermark = firstWatermark;
        this.endWatermark = endWatermark;
    }

    @Override
    public long watermark() {
        watermarkCalls++;
        return watermarkCalls <= 1 ? firstWatermark : endWatermark;
    }

    @Override
    public boolean healthy() {
        return healthy;
    }

    @Override
    public LinkedHashMap<String, SnapshotBatch> snapshot(long revision) {
        LinkedHashMap<String, SnapshotBatch> copy = new LinkedHashMap<>();
        for (var entry : batches.entrySet()) {
            SnapshotBatch fresh = SnapshotBatch.empty();
            fresh.merge(entry.getValue());
            copy.put(entry.getKey(), fresh);
        }
        return copy;
    }

    public FakeSnapshotSource healthy(boolean value) {
        this.healthy = value;
        return this;
    }

    public void resetWatermarkCalls() {
        this.watermarkCalls = 0;
    }
}

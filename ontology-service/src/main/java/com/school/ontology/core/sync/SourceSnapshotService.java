package com.school.ontology.core.sync;

import com.school.ontology.core.model.ModelDefinitionLoader;
import com.school.ontology.core.model.ModelRegistry;
import com.school.ontology.core.projection.SnapshotBatch;
import com.school.ontology.core.projection.SnapshotBuilder;
import com.school.ontology.core.spi.MappingContext;
import com.school.ontology.core.spi.SourceAdapter;
import com.school.ontology.core.spi.SourceQuery;
import com.school.ontology.core.spi.SourceRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;

/** 只读业务库快照实现：分页扫描来源表，用 YAML 定义 + SPI 生成投影批次。 */
@Component
public class SourceSnapshotService implements SnapshotSource {

    private final SourceAdapter adapter;
    private final ModelRegistry registry;
    private final ModelDefinitionLoader loader;
    private final SnapshotBuilder builder;
    private final int pageSize;

    public SourceSnapshotService(SourceAdapter adapter, ModelRegistry registry,
                                 ModelDefinitionLoader loader, SnapshotBuilder builder,
                                 @Value("${ontology.sync.import-page-size:500}") int pageSize) {
        this.adapter = adapter;
        this.registry = registry;
        this.loader = loader;
        this.builder = builder;
        this.pageSize = Math.max(pageSize, 1);
    }

    @Override
    public long watermark() {
        return adapter.currentWatermark();
    }

    @Override
    public boolean healthy() {
        return adapter.healthCheck();
    }

    @Override
    public LinkedHashMap<String, SnapshotBatch> snapshot(long revision) {
        MappingContext ctx = MappingContext.of(registry.sourceId(),
                loader.mappingVersion().mappingVersion, revision, adapter.lookups());
        LinkedHashMap<String, SnapshotBatch> result = new LinkedHashMap<>();
        for (String objectType : registry.orderedTypeIds()) {
            SnapshotBatch batch = SnapshotBatch.empty();
            int page = 1;
            while (true) {
                List<SourceRecord> records = adapter.scan(SourceQuery.page(objectType, page, pageSize));
                if (records.isEmpty()) {
                    break;
                }
                batch.merge(builder.buildAll(records, objectType, ctx));
                if (records.size() < pageSize) {
                    break;
                }
                page++;
            }
            result.put(objectType, batch);
        }
        return result;
    }
}

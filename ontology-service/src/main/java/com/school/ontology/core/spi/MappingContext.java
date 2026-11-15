package com.school.ontology.core.spi;

import java.time.LocalDateTime;

/** 投影构建上下文：来源标识、映射版本、版本水位与只读联表端口。 */
public record MappingContext(String sourceId, String mappingVersion, long revision,
                             LookupResolver lookups, LocalDateTime processedAt) {

    public static MappingContext of(String sourceId, String mappingVersion, long revision,
                                    LookupResolver lookups) {
        return new MappingContext(sourceId, mappingVersion, revision, lookups, LocalDateTime.now());
    }
}

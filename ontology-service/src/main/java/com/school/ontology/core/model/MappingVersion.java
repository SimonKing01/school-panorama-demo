package com.school.ontology.core.model;

import java.time.LocalDateTime;

/** 映射版本记录：定义内容哈希决定版本，对象/关系/来源引用都带该版本以便追溯。 */
public class MappingVersion {

    public final String sourceId;
    public final String mappingVersion;
    public final String definitionHash;
    public final LocalDateTime activatedAt;
    public final String status;

    public MappingVersion(String sourceId, String mappingVersion, String definitionHash,
                          LocalDateTime activatedAt, String status) {
        this.sourceId = sourceId;
        this.mappingVersion = mappingVersion;
        this.definitionHash = definitionHash;
        this.activatedAt = activatedAt;
        this.status = status;
    }
}

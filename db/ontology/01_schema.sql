-- 本体库（ontologydb）结构定义：14 张 ont_* 表。
-- 只在数据卷为空时由 PostgreSQL 官方镜像执行；本体服务启动迁移器会执行同一份
-- classpath:ontology/schema.sql 做兜底，因此全部语句必须幂等（IF NOT EXISTS）。
--
-- 表级策略（架构 §3.1 / Q2）：
--   当前投影（重建时覆盖）：ont_object / ont_relation / ont_source_ref / ont_object_version
--   模型与配置（保留）：ont_model_def / ont_mapping_version / ont_data_source
--   审计历史（全保留）：ont_change_history / ont_change_impact / ont_sync_task /
--                       ont_processed_event / ont_error_log / ont_reconcile_run
--   运行记录（保留且不回退）：ont_platform_revision

CREATE TABLE IF NOT EXISTS ont_model_def (
    def_key         VARCHAR(128) PRIMARY KEY,
    def_type        VARCHAR(32)  NOT NULL,
    content         JSONB        NOT NULL DEFAULT '{}'::jsonb,
    mapping_version VARCHAR(64)  NOT NULL,
    updated_at      TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ont_mapping_version (
    source_id        VARCHAR(64)  NOT NULL,
    mapping_version  VARCHAR(64)  NOT NULL,
    definition_hash  VARCHAR(128) NOT NULL,
    activated_at     TIMESTAMP    NOT NULL DEFAULT now(),
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    PRIMARY KEY (source_id, mapping_version)
);

CREATE TABLE IF NOT EXISTS ont_data_source (
    source_id               VARCHAR(64) PRIMARY KEY,
    display_name            VARCHAR(128) NOT NULL,
    source_up               BOOLEAN   NOT NULL DEFAULT FALSE,
    status                  VARCHAR(24) NOT NULL DEFAULT 'NOT_CONNECTED',
    last_success_sync_at    TIMESTAMP,
    snapshot_revision       BIGINT    NOT NULL DEFAULT 0,
    current_source_revision BIGINT    NOT NULL DEFAULT 0,
    demo_time               TIMESTAMP,
    demo_running            BOOLEAN   NOT NULL DEFAULT FALSE,
    anchor_real_at          TIMESTAMP,
    pending_count           BIGINT    NOT NULL DEFAULT 0,
    processed_count         BIGINT    NOT NULL DEFAULT 0,
    error_count             BIGINT    NOT NULL DEFAULT 0,
    dead_count              BIGINT    NOT NULL DEFAULT 0,
    mapping_version         VARCHAR(64),
    updated_at              TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ont_object (
    global_id        VARCHAR(256) PRIMARY KEY,          -- gid:{sourceId}:{objectType}:{sourcePk}
    source_id        VARCHAR(64)  NOT NULL,
    object_type      VARCHAR(64)  NOT NULL,
    source_pk        VARCHAR(128) NOT NULL,
    title            VARCHAR(256),
    attributes       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    mapping_version  VARCHAR(64)  NOT NULL,
    applied_revision BIGINT       NOT NULL DEFAULT 0,  -- 版本水位：旧事件/旧快照不覆盖新状态
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_object_source UNIQUE (source_id, object_type, source_pk)
);
CREATE INDEX IF NOT EXISTS idx_obj_type     ON ont_object (object_type) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_obj_rev      ON ont_object (applied_revision);
CREATE INDEX IF NOT EXISTS idx_obj_attr_gin ON ont_object USING GIN (attributes jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_obj_name     ON ont_object ((attributes->>'name')) WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_obj_code     ON ont_object ((attributes->>'code')) WHERE deleted = FALSE;

CREATE TABLE IF NOT EXISTS ont_relation (
    relation_id      VARCHAR(768) PRIMARY KEY,         -- {relationType}:{from}:{to}:{validFrom}
    relation_type    VARCHAR(64)  NOT NULL,
    from_global_id   VARCHAR(256) NOT NULL,
    to_global_id     VARCHAR(256) NOT NULL,
    reason           TEXT         NOT NULL,            -- 存在原因：外键/联表/计算规则
    source_table     VARCHAR(64),
    source_pk        VARCHAR(128),
    source_hash      VARCHAR(64),
    mapping_version  VARCHAR(64)  NOT NULL,
    valid_from       TIMESTAMP,
    valid_to         TIMESTAMP,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    applied_revision BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_relation UNIQUE (relation_type, from_global_id, to_global_id, valid_from)
);
CREATE INDEX IF NOT EXISTS idx_rel_from ON ont_relation (from_global_id, active);
CREATE INDEX IF NOT EXISTS idx_rel_to   ON ont_relation (to_global_id,   active);
CREATE INDEX IF NOT EXISTS idx_rel_type ON ont_relation (relation_type, active);

CREATE TABLE IF NOT EXISTS ont_source_ref (
    global_id          VARCHAR(256) NOT NULL,
    source_id          VARCHAR(64)  NOT NULL,
    source_table       VARCHAR(64)  NOT NULL,
    source_pk          VARCHAR(128) NOT NULL,
    source_code        VARCHAR(64),
    source_hash        VARCHAR(64),
    mapping_version    VARCHAR(64)  NOT NULL,
    last_seen_revision BIGINT,
    PRIMARY KEY (global_id, source_id)
);
CREATE INDEX IF NOT EXISTS idx_srcref_pk ON ont_source_ref (source_table, source_pk);

CREATE TABLE IF NOT EXISTS ont_object_version (
    id          BIGSERIAL PRIMARY KEY,
    global_id   VARCHAR(256) NOT NULL,
    revision    BIGINT       NOT NULL,
    snapshot    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    event_id    VARCHAR(64),
    recorded_at TIMESTAMP    NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_objver_gid ON ont_object_version (global_id, revision);

CREATE TABLE IF NOT EXISTS ont_change_history (
    change_id         BIGSERIAL PRIMARY KEY,
    event_id          VARCHAR(64),
    global_id         VARCHAR(256) NOT NULL,
    change_kind       VARCHAR(32)  NOT NULL,
    origin            VARCHAR(16)  NOT NULL,   -- EVENT / RECONCILE / REBUILD
    before_json       JSONB,
    after_json        JSONB,
    diff_json         JSONB,
    relations_added   JSONB,
    relations_removed JSONB,
    reason            TEXT,
    operator          VARCHAR(64),            -- 对账/重建修复为 NULL，不伪造操作人
    business_time     TIMESTAMP,             -- 演示业务时间；对账/重建为 NULL，不伪造发生时间
    processed_at      TIMESTAMP    NOT NULL DEFAULT now(),
    mapping_version   VARCHAR(64)
);
CREATE INDEX IF NOT EXISTS idx_chg_obj ON ont_change_history (global_id, processed_at DESC);
CREATE INDEX IF NOT EXISTS idx_chg_evt ON ont_change_history (event_id);

CREATE TABLE IF NOT EXISTS ont_change_impact (
    change_id BIGINT       NOT NULL,
    global_id VARCHAR(256) NOT NULL,
    role      VARCHAR(24)  NOT NULL,   -- SELF_CHANGED / DERIVED_RELATED
    basis     TEXT,
    PRIMARY KEY (change_id, global_id)
);
CREATE INDEX IF NOT EXISTS idx_impact_obj ON ont_change_impact (global_id);

CREATE TABLE IF NOT EXISTS ont_processed_event (
    event_id     VARCHAR(64) PRIMARY KEY,
    revision     BIGINT    NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ont_sync_task (
    task_id           BIGSERIAL PRIMARY KEY,
    task_type         VARCHAR(32) NOT NULL,   -- FULL_IMPORT / REBUILD / CATCHUP / RECONCILE
    status            VARCHAR(16) NOT NULL,   -- RUNNING / SUCCESS / FAILED
    snapshot_revision BIGINT      NOT NULL DEFAULT 0,
    end_revision      BIGINT      NOT NULL DEFAULT 0,
    progress_json     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    error             TEXT,
    operator          VARCHAR(64) NOT NULL DEFAULT 'system',
    started_at        TIMESTAMP   NOT NULL DEFAULT now(),
    finished_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_task_status ON ont_sync_task (task_type, status, started_at DESC);

CREATE TABLE IF NOT EXISTS ont_error_log (
    error_id   BIGSERIAL PRIMARY KEY,
    event_id   VARCHAR(64),
    task_id    BIGINT,
    phase      VARCHAR(32) NOT NULL,
    message    TEXT,
    retryable  BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ont_platform_revision (
    id         INTEGER PRIMARY KEY DEFAULT 1,
    revision   BIGINT    NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);
INSERT INTO ont_platform_revision (id, revision) VALUES (1, 0) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ont_reconcile_run (
    run_id       BIGSERIAL PRIMARY KEY,
    status       VARCHAR(16) NOT NULL,
    diff_json    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    summary_json JSONB       NOT NULL DEFAULT '{}'::jsonb,
    started_at   TIMESTAMP   NOT NULL DEFAULT now(),
    finished_at  TIMESTAMP
);

-- 在线编辑关系类型（07 增量设计 §5.2）：版本表补 operator / note / definition_source，
-- 模型定义表同样补三列，供管理页版本时间线展示「谁、为什么、来自 BASELINE 还是 OVERLAY」。
-- 与 classpath:ontology/schema.sql 保持完全一致，全部幂等。
ALTER TABLE ont_mapping_version ADD COLUMN IF NOT EXISTS operator VARCHAR(64);
ALTER TABLE ont_mapping_version ADD COLUMN IF NOT EXISTS note TEXT;
ALTER TABLE ont_mapping_version ADD COLUMN IF NOT EXISTS definition_source VARCHAR(24);
ALTER TABLE ont_model_def ADD COLUMN IF NOT EXISTS operator VARCHAR(64);
ALTER TABLE ont_model_def ADD COLUMN IF NOT EXISTS note TEXT;
ALTER TABLE ont_model_def ADD COLUMN IF NOT EXISTS definition_source VARCHAR(24);

-- 原业务库 Outbox 表：只在数据卷为空时由 PostgreSQL 官方镜像执行。
-- 已有数据卷（school-panorama_dbdata）靠 backend 的 OutboxSchemaMigrator
-- 启动时幂等创建；严禁依赖删卷重建（A6）。本脚本与迁移器 DDL 必须一致。

CREATE TABLE IF NOT EXISTS outbox_events (
    event_id       VARCHAR(64) PRIMARY KEY,          -- 唯一 ID：应用层生成，事务内写入
    batch_id       VARCHAR(64),
    aggregate_type VARCHAR(32) NOT NULL,            -- LESSON_SESSION / DEMO_STATE / BATCH / SNAPSHOT
    aggregate_id   VARCHAR(64),
    change_kind    VARCHAR(24) NOT NULL,            -- ADJUST/BATCH_ADJUST/CLOCK/SYNC_TODAY/RESET/DELETE
    revision       BIGINT      NOT NULL,            -- 业务提交时的 system_revision
    payload        JSONB       NOT NULL,            -- 前后状态或等价信息
    business_time  TIMESTAMP,                      -- 演示业务时间 demoTime（业务事实，可回拨）
    occurred_at    TIMESTAMP   NOT NULL DEFAULT now(), -- 事件入库真实处理时间（单调）
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/IN_FLIGHT/FAILED/DELIVERED/DEAD
    attempts       INT         NOT NULL DEFAULT 0,
    next_retry_at  TIMESTAMP,
    last_error     TEXT,
    delivered_at   TIMESTAMP,
    created_at     TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending
    ON outbox_events (status, next_retry_at, revision);
CREATE INDEX IF NOT EXISTS idx_outbox_rev ON outbox_events (revision);

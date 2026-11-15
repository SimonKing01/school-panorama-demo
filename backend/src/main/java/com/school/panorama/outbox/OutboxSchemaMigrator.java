package com.school.panorama.outbox;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 业务库 Outbox 表启动幂等迁移器（A6 硬性）。
 *
 * <p>背景：{@code db/init/*.sql} 只在数据卷为空时由镜像执行，而本项目已有
 * {@code school-panorama_dbdata} 数据卷正在运行。因此 outbox_events 必须由业务服务
 * 每次启动时用 {@code CREATE TABLE IF NOT EXISTS} 幂等创建，绝不删卷重建、
 * 绝不改动任何已有表的列、绝不回填历史（未捕获的历史不伪造操作人/时间）。</p>
 *
 * <p>本文件是批次① 在 backend 下唯一新增的文件；不修改任何现有业务类，
 * 也不触碰业务库中的业务数据。</p>
 */
@Component
public class OutboxSchemaMigrator implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public OutboxSchemaMigrator(DataSource dataSource) {
        // 显式构造，避免依赖任何自动配置状态；连接的就是原业务库（业务服务唯一写者）。
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public void run(ApplicationArguments args) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS outbox_events (
                    event_id       VARCHAR(64) PRIMARY KEY,
                    batch_id       VARCHAR(64),
                    aggregate_type VARCHAR(32) NOT NULL,
                    aggregate_id   VARCHAR(64),
                    change_kind    VARCHAR(24) NOT NULL,
                    revision       BIGINT      NOT NULL,
                    payload        JSONB       NOT NULL,
                    business_time  TIMESTAMP,
                    occurred_at    TIMESTAMP   NOT NULL DEFAULT now(),
                    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts       INT         NOT NULL DEFAULT 0,
                    next_retry_at  TIMESTAMP,
                    last_error     TEXT,
                    delivered_at   TIMESTAMP,
                    created_at     TIMESTAMP   NOT NULL DEFAULT now()
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_pending "
                + "ON outbox_events (status, next_retry_at, revision)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_outbox_rev ON outbox_events (revision)");
    }
}

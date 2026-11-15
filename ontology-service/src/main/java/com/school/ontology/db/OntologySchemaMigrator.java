package com.school.ontology.db;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 本体库启动幂等建表兜底（架构 §4.2）。
 *
 * <p>新卷由 PostgreSQL 镜像执行 {@code db/ontology/01_schema.sql}；已有卷或
 * 独立启动时由本迁移器执行 classpath 中同内容的脚本。全部
 * {@code CREATE TABLE/INDEX IF NOT EXISTS}，绝不 DROP、绝不删卷、绝不回填历史。</p>
 *
 * <p>顺序先于 MappingVersionRecorder（@Order 10 < 20），保证版本记录写入时表已存在。</p>
 */
@Component
@Order(10)
public class OntologySchemaMigrator implements ApplicationRunner, Ordered {

    private final DataSource dataSource;

    public OntologySchemaMigrator(@Qualifier("ontologyDataSource") DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public void run(ApplicationArguments args) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("ontology/schema.sql"));
        populator.setSeparator(";");
        populator.setContinueOnError(false);
        populator.setIgnoreFailedDrops(true);
        populator.execute(Objects.requireNonNull(new JdbcTemplate(dataSource).getDataSource(),
                "本体库数据源不可为空"));
    }
}

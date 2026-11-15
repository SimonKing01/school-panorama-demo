package com.school.ontology.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 双数据源隔离配置（架构 §1.3、§11-A）。
 *
 * <p>主数据源 = 本体库（唯一写者）；只读数据源 = 原业务库，独立 Hikari 池，
 * 且每个连接强制 {@code Connection.setReadOnly(true)}。代码层约定该数据源
 * 只出现 SELECT，绝不出现 INSERT/UPDATE/DELETE/TRUNCATE。部署时仍应把
 * {@code SOURCE_DB_USER} 换成数据库只读账号，形成「配置 + 代码 + 账号」三重约束。</p>
 *
 * <p>只有一个事务管理器（本体库），因此所有 {@code @Transactional} 天然落在
 * 本体库单事务内，符合「底座侧单次消费一个事务、提交后才 ACK」的约束。</p>
 */
@Configuration
public class DataSourceConfig {

    @Bean(name = "ontologyDataSource")
    @Primary
    public DataSource ontologyDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ontology-main-pool");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(maxPoolSize);
        return new HikariDataSource(config);
    }

    @Bean(name = "sourceDataSource")
    public DataSource sourceDataSource(
            @Value("${source-db.url}") String url,
            @Value("${source-db.username}") String username,
            @Value("${source-db.password}") String password,
            @Value("${source-db.hikari.maximum-pool-size:4}") int maxPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ontology-source-readonly-pool");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(maxPoolSize);
        // 与主池物理隔离：并发导入不会挤占本体库写入连接。
        return new ReadOnlyDataSource(new HikariDataSource(config));
    }

    @Bean(name = "ontologyJdbc")
    @Primary
    public JdbcTemplate ontologyJdbc(@Qualifier("ontologyDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "sourceJdbc")
    public JdbcTemplate sourceJdbc(@Qualifier("sourceDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "ontologyTransactionManager")
    @Primary
    public PlatformTransactionManager ontologyTransactionManager(
            @Qualifier("ontologyDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 只读数据源包装：每次取连接都显式设置 {@code setReadOnly(true)}，
     * 避免依赖连接池实现细节。业务库写操作在数据库层也会被拒绝（只读事务）。
     */
    private static final class ReadOnlyDataSource extends DelegatingDataSource {

        private ReadOnlyDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            connection.setReadOnly(true);
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection connection = super.getConnection(username, password);
            connection.setReadOnly(true);
            return connection;
        }
    }
}

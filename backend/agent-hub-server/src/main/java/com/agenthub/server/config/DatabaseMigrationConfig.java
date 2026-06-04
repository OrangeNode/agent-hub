package com.agenthub.server.config;

import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseMigrationConfig {

    private static final List<String> LEGACY_IDENTITY_TABLES = List.of(
            "sys_user",
            "sys_role",
            "sys_menu",
            "sys_user_role",
            "sys_role_menu"
    );

    @Bean
    @ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
    ApplicationRunner databaseMigrationRunner(DataSource dataSource) {
        return args -> {
            repairLegacyPostgresIdentitySequences(dataSource);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .validateOnMigrate(true)
                    .load()
                    .migrate();
        };
    }

    private void repairLegacyPostgresIdentitySequences(DataSource dataSource) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            if (!"PostgreSQL".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
                return;
            }
        }

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Boolean rbacTablesExist = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.sys_user') IS NOT NULL",
                Boolean.class
        );
        if (!Boolean.TRUE.equals(rbacTablesExist)) {
            return;
        }

        for (String tableName : LEGACY_IDENTITY_TABLES) {
            // 兼容旧开发库：V1 曾显式插入主键，确保后续迁移生成 ID 不会撞到已有数据。
            jdbcTemplate.execute("""
                    SELECT setval(
                        pg_get_serial_sequence('%s', 'id'),
                        GREATEST(COALESCE((SELECT MAX(id) FROM %s), 1), 1),
                        TRUE
                    )
                    """.formatted(tableName, tableName));
        }
    }
}

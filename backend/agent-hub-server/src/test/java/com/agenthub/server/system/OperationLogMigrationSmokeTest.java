package com.agenthub.server.system;

import static com.agenthub.server.support.MigrationTestSupport.readConsolidatedSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:operation_log_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class OperationLogMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateOperationLogTable() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name = 'operation_log'
                """, String.class);

        assertThat(tableNames).containsExactly("operation_log");
    }

    @Test
    void shouldCreateOperationLogIndexes() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE LOWER(table_schema) = 'public'
                    AND LOWER(table_name) = 'operation_log'
                """, String.class);

        assertThat(indexes)
                .anyMatch(name -> name.equalsIgnoreCase("idx_operation_log_created_at"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_operation_log_user_created_at"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_operation_log_module_action"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_operation_log_status_created_at"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_operation_log_target_created_at"));
    }

    @Test
    void shouldInitializeSandboxAndOperationLogMenus() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> menus = jdbcTemplate.queryForList("""
                SELECT name, title, path, permission
                FROM sys_menu
                WHERE name IN ('sandbox', 'sandboxMonitor', 'operationLogs')
                """);

        assertThat(menus)
                .extracting(row -> row.get("name") + ":" + row.get("title") + ":" + row.get("path") + ":" + row.get("permission"))
                .containsExactlyInAnyOrder(
                        "sandbox:Sandbox 管理:/sandbox:null",
                        "sandboxMonitor:Sandbox 运维:/sandbox/monitor:sandbox:monitor:view",
                        "operationLogs:操作日志:/system/operation-logs:operation-log:view"
                );
    }

    @Test
    void shouldBindAdminRoleToOperationPermissions() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> permissions = jdbcTemplate.queryForList("""
                SELECT sm.permission
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.permission IN (
                        'sandbox:monitor:view',
                        'sandbox:runtime:reap',
                        'sandbox:session:close',
                        'sandbox:runtime:cleanup',
                        'sandbox:runtime:kill',
                        'operation-log:view'
                    )
                ORDER BY sm.permission
                """, String.class);

        assertThat(permissions).containsExactly(
                "operation-log:view",
                "sandbox:monitor:view",
                "sandbox:runtime:cleanup",
                "sandbox:runtime:kill",
                "sandbox:runtime:reap",
                "sandbox:session:close"
        );
    }

    @Test
    void shouldDefineOperationLogAndSandboxMenusInConsolidatedMigration() throws IOException {
        String scriptContent = readConsolidatedSchema();

        assertThat(scriptContent)
                .contains(
                        "SELECT 0, 'sandbox', 'Sandbox 管理'",
                        "SELECT parent.id, 'sandboxMonitor', 'Sandbox 运维'",
                        "SELECT parent.id, 'operationLogs', '操作日志'",
                        "'operation-log:view'",
                        "'sandbox:monitor:view'"
                );
    }
}

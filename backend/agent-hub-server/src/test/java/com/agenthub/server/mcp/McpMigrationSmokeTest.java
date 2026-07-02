package com.agenthub.server.mcp;

import static com.agenthub.server.support.MigrationTestSupport.readConsolidatedSchema;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.tool.mcp.mapper.McpServerMapper;
import com.agenthub.tool.mcp.mapper.McpToolCallLogMapper;
import com.agenthub.tool.mcp.mapper.McpToolMapper;
import java.io.IOException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:mcp_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class McpMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private McpServerMapper serverMapper;

    @Autowired
    private McpToolMapper toolMapper;

    @Autowired
    private McpToolCallLogMapper callLogMapper;

    @Test
    void shouldCreateMcpTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('mcp_server', 'mcp_tool', 'mcp_tool_call_log')
                ORDER BY table_name
                """, String.class);

        assertThat(tableNames).containsExactly("mcp_server", "mcp_tool", "mcp_tool_call_log");
    }

    @Test
    void shouldCreateMcpIndexesAndConstraints() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name IN ('mcp_server', 'mcp_tool', 'mcp_tool_call_log')
                """, String.class);

        assertThat(constraints).contains(
                "uk_mcp_server_key",
                "uk_mcp_tool_server_name",
                "fk_mcp_tool_server",
                "fk_mcp_call_server",
                "fk_mcp_call_tool",
                "fk_mcp_call_conversation",
                "fk_mcp_call_message"
        );

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE LOWER(table_schema) = 'public'
                    AND LOWER(table_name) IN ('mcp_server', 'mcp_tool', 'mcp_tool_call_log')
                """, String.class);

        assertThat(indexes)
                .anyMatch(name -> name.equalsIgnoreCase("idx_mcp_server_enabled_sort"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_mcp_tool_server_enabled"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_mcp_call_conversation_started"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_mcp_call_server_started"));
    }

    @Test
    void shouldRegisterMcpMappers() {
        assertThat(serverMapper).isNotNull();
        assertThat(toolMapper).isNotNull();
        assertThat(callLogMapper).isNotNull();
    }

    @Test
    void shouldAllowFullLengthMcpExposedToolNameInCallLog() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer exposedToolNameLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'mcp_tool_call_log'
                    AND column_name = 'exposed_tool_name'
                """, Integer.class);

        assertThat(exposedToolNameLength).isGreaterThanOrEqualTo(320);
    }

    @Test
    void shouldRegisterMcpMenuAndPermissions() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer menuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE name = 'mcpServers'
                    AND path = '/ai/mcp-servers'
                    AND permission = 'mcp:server:list'
                """, Integer.class);

        assertThat(menuCount).isEqualTo(1);

        List<String> permissions = jdbcTemplate.queryForList("""
                SELECT sm.permission
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.permission IN (
                        'mcp:server:list',
                        'mcp:server:create',
                        'mcp:server:update',
                        'mcp:server:delete',
                        'mcp:server:test',
                        'mcp:tool:sync',
                        'mcp:tool:update',
                        'mcp:call:list'
                    )
                ORDER BY sm.permission
                """, String.class);

        assertThat(permissions).containsExactly(
                "mcp:call:list",
                "mcp:server:create",
                "mcp:server:delete",
                "mcp:server:list",
                "mcp:server:test",
                "mcp:server:update",
                "mcp:tool:sync",
                "mcp:tool:update"
        );
    }

    @Test
    void shouldDefineMcpMenuInConsolidatedMigration() throws IOException {
        String scriptContent = readConsolidatedSchema();

        assertThat(scriptContent)
                .contains(
                        "SELECT parent.id, 'mcpServers', 'MCP 服务'",
                        "'mcp:server:list'",
                        "'mcp:tool:sync'",
                        "'mcp:call:list'"
                );
    }
}

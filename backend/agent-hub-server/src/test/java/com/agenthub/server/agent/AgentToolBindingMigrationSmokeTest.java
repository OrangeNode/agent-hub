package com.agenthub.server.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.tool.binding.mapper.AgentToolBindingMapper;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_tool_binding_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class AgentToolBindingMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AgentToolBindingMapper bindingMapper;

    @Test
    void shouldCreateAgentToolBindingTable() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name = 'agent_tool_binding'
                """, String.class);

        assertThat(tableNames).containsExactly("agent_tool_binding");
    }

    @Test
    void shouldCreateAgentToolBindingConstraintsAndIndexes() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name = 'agent_tool_binding'
                """, String.class);

        assertThat(constraints).contains(
                "fk_agent_tool_agent",
                "uk_agent_tool_binding"
        );

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE LOWER(table_schema) = 'public'
                    AND LOWER(table_name) = 'agent_tool_binding'
                """, String.class);

        assertThat(indexes)
                .anyMatch(name -> name.equalsIgnoreCase("idx_agent_tool_agent_sort"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_agent_tool_provider_ref"));
    }

    @Test
    void shouldAllowFullLengthMcpExposedToolName() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer toolNameLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'agent_tool_binding'
                    AND column_name = 'tool_name'
                """, Integer.class);

        assertThat(toolNameLength).isGreaterThanOrEqualTo(320);
    }

    @Test
    void shouldRegisterAgentToolBindingMapper() {
        assertThat(bindingMapper).isNotNull();
    }

    @Test
    void shouldRegisterAgentToolBindingPermissionForAdmin() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Integer buttonCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_menu
                WHERE name = 'agentToolUpdate'
                    AND permission = 'agent:tool:update'
                    AND menu_type = 'BUTTON'
                """, Integer.class);

        assertThat(buttonCount).isEqualTo(1);

        Integer adminPermissionCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.permission = 'agent:tool:update'
                """, Integer.class);

        assertThat(adminPermissionCount).isEqualTo(1);
    }
}

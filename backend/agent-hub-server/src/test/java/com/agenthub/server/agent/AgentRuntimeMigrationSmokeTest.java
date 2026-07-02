package com.agenthub.server.agent;

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
        "spring.datasource.url=jdbc:h2:mem:agent_runtime_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class AgentRuntimeMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateAgentConfigTable() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name = 'agent_config'
                """, String.class);

        assertThat(tableNames).containsExactly("agent_config");
    }

    @Test
    void shouldInitializeDefaultAgents() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> agents = jdbcTemplate.queryForList("""
                SELECT agent_key, name, enabled
                FROM agent_config
                ORDER BY sort ASC
                """);

        assertThat(agents)
                .extracting(row -> row.get("agent_key") + ":" + row.get("name") + ":" + row.get("enabled"))
                .contains(
                        "document-review:文档审核 Agent:true",
                        "official-writing:公文写作 Agent:true",
                        "data-analysis:数据分析 Agent:true",
                        "project-plan:项目方案 Agent:true"
                );
    }

    @Test
    void shouldInitializeAgentMenuAndBindAdminRole() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> menus = jdbcTemplate.queryForList("""
                SELECT name, title
                FROM sys_menu
                WHERE parent_id = 0
                    AND name = 'agent'
                UNION ALL
                SELECT child.name, child.title
                FROM sys_menu root
                JOIN sys_menu child ON child.parent_id = root.id
                WHERE root.parent_id = 0
                    AND root.name = 'agent'
                    AND child.name = 'agentList'
                """);

        assertThat(menus)
                .extracting(row -> row.get("name") + ":" + row.get("title"))
                .containsExactlyInAnyOrder("agent:Agent 管理", "agentList:Agent 列表");

        Integer adminMenuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu root ON root.parent_id = 0
                    AND root.name = 'agent'
                JOIN sys_menu sm ON sm.id = srm.menu_id
                    AND (
                        sm.id = root.id
                        OR sm.parent_id = root.id
                    )
                WHERE sr.code = 'ADMIN'
                    AND sm.name IN ('agent', 'agentList')
                """, Integer.class);

        assertThat(adminMenuCount).isEqualTo(2);
    }

    @Test
    void shouldDefineAgentMenuWithParentLookupInConsolidatedMigration() throws IOException {
        String scriptContent = readConsolidatedSchema();

        assertThat(scriptContent)
                .contains(
                        "SELECT 0, 'agent', 'Agent 管理'",
                        "SELECT parent.id, 'agentList', 'Agent 列表'",
                        "WHERE parent.parent_id = 0"
                );
    }
}

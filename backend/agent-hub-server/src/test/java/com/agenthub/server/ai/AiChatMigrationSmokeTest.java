package com.agenthub.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_chat_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class AiChatMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateAiAndChatTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('ai_provider', 'ai_model', 'chat_conversation', 'chat_message')
                """, String.class);

        assertThat(tableNames)
                .containsExactlyInAnyOrder("ai_provider", "ai_model", "chat_conversation", "chat_message");
    }

    @Test
    void shouldConstrainConversationTitleLengthAndNullability() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        Map<String, Object> titleColumn = jdbcTemplate.queryForMap("""
                SELECT character_maximum_length, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'chat_conversation'
                    AND column_name = 'title'
                """);

        assertThat(((Number) titleColumn.get("character_maximum_length")).longValue()).isEqualTo(30L);
        assertThat(titleColumn.get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void shouldInitializeAiMenusAndBindAdminRole() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> menus = jdbcTemplate.queryForList("""
                SELECT name, title
                FROM sys_menu
                WHERE name IN ('ai', 'aiProviders', 'aiModels')
                """);

        assertThat(menus)
                .extracting(row -> row.get("name") + ":" + row.get("title"))
                .containsExactlyInAnyOrder("ai:AI 管理", "aiProviders:供应商管理", "aiModels:模型管理");

        Integer adminMenuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.name IN ('ai', 'aiProviders', 'aiModels')
                """, Integer.class);

        assertThat(adminMenuCount).isEqualTo(3);
    }

    @Test
    void shouldAllowGeneratedIdsAfterAiMenuMigration() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
                VALUES (0, 'migrationIdentitySmoke', '迁移测试菜单', '/migration-identity-smoke', NULL, NULL, 'test:menu:list', 'MENU', TRUE, 99, 1, 1)
                """);

        jdbcTemplate.update("""
                INSERT INTO sys_role_menu (role_id, menu_id, created_by, updated_by)
                SELECT sr.id, sm.id, 1, 1
                FROM sys_role sr
                JOIN sys_menu sm ON sm.name = 'migrationIdentitySmoke'
                WHERE sr.code = 'ADMIN'
                """);

        Integer relationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.name = 'migrationIdentitySmoke'
                """, Integer.class);

        assertThat(relationCount).isEqualTo(1);
    }

    @Test
    void shouldNotHardCodeAiMenuIdsOrRestartIdentityInMigration() throws IOException {
        var migrationScriptUrl = AiChatMigrationSmokeTest.class.getResource("/db/migration/V2__init_ai_chat.sql");

        assertThat(migrationScriptUrl).isNotNull();

        String scriptContent;
        try (var inputStream = migrationScriptUrl.openStream()) {
            scriptContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(scriptContent).doesNotContain("INSERT INTO sys_menu (id");
        assertThat(scriptContent).doesNotContain("INSERT INTO sys_role_menu (id");
        assertThat(scriptContent).doesNotContain("RESTART WITH");
    }
}

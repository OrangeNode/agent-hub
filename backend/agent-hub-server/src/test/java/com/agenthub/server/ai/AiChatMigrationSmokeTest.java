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
    void shouldAllowDeletingAiConfigWhileKeepingChatHistory() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO ai_provider (id, code, name, provider_type, protocol_type, base_url, auth_type)
                VALUES (10001, 'delete-model-provider', '删除模型供应商', 'LLM', 'OPENAI_COMPATIBLE', 'https://api.example.com', 'BEARER')
                """);
        jdbcTemplate.update("""
                INSERT INTO ai_model (id, provider_id, model_code, display_name, capability_type)
                VALUES (10002, 10001, 'legacy-model', 'Legacy Model', 'CHAT')
                """);
        jdbcTemplate.update("""
                INSERT INTO chat_conversation (id, user_id, title, model_id, provider_id)
                VALUES (10003, 1, '删除模型历史', 10002, 10001)
                """);
        jdbcTemplate.update("""
                INSERT INTO chat_message (id, conversation_id, user_id, role, content, model_id, provider_id)
                VALUES (10004, 10003, 1, 'ASSISTANT', '历史回复', 10002, 10001)
                """);

        jdbcTemplate.update("DELETE FROM ai_model WHERE id = 10002");

        Map<String, Object> conversationAfterModelDelete = jdbcTemplate.queryForMap("""
                SELECT model_id, provider_id
                FROM chat_conversation
                WHERE id = 10003
                """);
        Map<String, Object> messageAfterModelDelete = jdbcTemplate.queryForMap("""
                SELECT model_id, provider_id
                FROM chat_message
                WHERE id = 10004
                """);

        assertThat(conversationAfterModelDelete.get("model_id")).isNull();
        assertThat(conversationAfterModelDelete.get("provider_id")).isEqualTo(10001L);
        assertThat(messageAfterModelDelete.get("model_id")).isNull();
        assertThat(messageAfterModelDelete.get("provider_id")).isEqualTo(10001L);

        jdbcTemplate.update("""
                INSERT INTO ai_provider (id, code, name, provider_type, protocol_type, base_url, auth_type)
                VALUES (10011, 'delete-provider', '删除供应商', 'LLM', 'OPENAI_COMPATIBLE', 'https://api.example.com', 'BEARER')
                """);
        jdbcTemplate.update("""
                INSERT INTO ai_model (id, provider_id, model_code, display_name, capability_type)
                VALUES (10012, 10011, 'provider-model', 'Provider Model', 'CHAT')
                """);
        jdbcTemplate.update("""
                INSERT INTO chat_conversation (id, user_id, title, model_id, provider_id)
                VALUES (10013, 1, '删除供应商历史', 10012, 10011)
                """);
        jdbcTemplate.update("""
                INSERT INTO chat_message (id, conversation_id, user_id, role, content, model_id, provider_id)
                VALUES (10014, 10013, 1, 'ASSISTANT', '历史回复', 10012, 10011)
                """);

        jdbcTemplate.update("DELETE FROM ai_provider WHERE id = 10011");

        Integer modelCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model WHERE id = 10012",
                Integer.class
        );
        Map<String, Object> conversationAfterProviderDelete = jdbcTemplate.queryForMap("""
                SELECT model_id, provider_id
                FROM chat_conversation
                WHERE id = 10013
                """);
        Map<String, Object> messageAfterProviderDelete = jdbcTemplate.queryForMap("""
                SELECT model_id, provider_id
                FROM chat_message
                WHERE id = 10014
                """);

        assertThat(modelCount).isZero();
        assertThat(conversationAfterProviderDelete.get("model_id")).isNull();
        assertThat(conversationAfterProviderDelete.get("provider_id")).isNull();
        assertThat(messageAfterProviderDelete.get("model_id")).isNull();
        assertThat(messageAfterProviderDelete.get("provider_id")).isNull();
    }

    @Test
    void shouldInitializeAiMenusAndBindAdminRole() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<Map<String, Object>> menus = jdbcTemplate.queryForList("""
                SELECT name, title
                FROM sys_menu
                WHERE parent_id = 0
                    AND name = 'ai'
                UNION ALL
                SELECT child.name, child.title
                FROM sys_menu root
                JOIN sys_menu child ON child.parent_id = root.id
                WHERE root.parent_id = 0
                    AND root.name = 'ai'
                    AND child.name IN ('aiProviders', 'aiModels')
                """);

        assertThat(menus)
                .extracting(row -> row.get("name") + ":" + row.get("title"))
                .containsExactlyInAnyOrder("ai:AI 管理", "aiProviders:供应商管理", "aiModels:模型管理");

        Integer adminMenuCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu root ON root.parent_id = 0
                    AND root.name = 'ai'
                JOIN sys_menu sm ON sm.id = srm.menu_id
                    AND (
                        sm.id = root.id
                        OR sm.parent_id = root.id
                    )
                WHERE sr.code = 'ADMIN'
                    AND sm.name IN ('ai', 'aiProviders', 'aiModels')
                """, Integer.class);

        assertThat(adminMenuCount).isEqualTo(3);
    }

    @Test
    void shouldNotAuthorizeSameNamedMenusOutsideTargetAiTree() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO sys_menu (parent_id, name, title, path, component, icon, permission, menu_type, visible, sort, created_by, updated_by)
                VALUES (2, 'aiProviders', '非目标供应商菜单', '/system/ai-providers-shadow', NULL, NULL, 'shadow:provider:list', 'MENU', TRUE, 99, 1, 1)
                """);

        Integer adminBoundTargetAiMenus = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu root ON root.parent_id = 0
                    AND root.name = 'ai'
                JOIN sys_menu sm ON sm.id = srm.menu_id
                    AND (
                        sm.id = root.id
                        OR sm.parent_id = root.id
                    )
                WHERE sr.code = 'ADMIN'
                    AND sm.name IN ('ai', 'aiProviders', 'aiModels')
                """, Integer.class);

        Integer adminBoundShadowMenus = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sys_role_menu srm
                JOIN sys_role sr ON sr.id = srm.role_id
                JOIN sys_menu sm ON sm.id = srm.menu_id
                WHERE sr.code = 'ADMIN'
                    AND sm.path = '/system/ai-providers-shadow'
                """, Integer.class);

        assertThat(adminBoundTargetAiMenus).isEqualTo(3);
        assertThat(adminBoundShadowMenus).isZero();
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
        assertThat(scriptContent).doesNotContain("WHERE parent.name = 'ai'");
        assertThat(scriptContent).doesNotContain("JOIN sys_menu sm ON sm.name IN");
        assertThat(scriptContent).contains("parent.parent_id = 0");
    }

    @Test
    void shouldKeepExistingVersionThreeMigrationAndMoveDeleteConstraintChangeToVersionFour() throws IOException {
        var historicalVersionThreeUrl = AiChatMigrationSmokeTest.class
                .getResource("/db/migration/V3__init_multimodal_image_summary.sql");
        var conflictingVersionThreeUrl = AiChatMigrationSmokeTest.class
                .getResource("/db/migration/V3__relax_ai_config_delete_constraints.sql");
        var deleteConstraintMigrationUrl = AiChatMigrationSmokeTest.class
                .getResource("/db/migration/V4__relax_ai_config_delete_constraints.sql");
        var contextIndexMigrationUrl = AiChatMigrationSmokeTest.class
                .getResource("/db/migration/V5__add_chat_message_context_index.sql");

        assertThat(historicalVersionThreeUrl).isNotNull();
        assertThat(conflictingVersionThreeUrl).isNull();
        assertThat(deleteConstraintMigrationUrl).isNotNull();
        assertThat(contextIndexMigrationUrl).isNotNull();

        String scriptContent;
        try (var inputStream = deleteConstraintMigrationUrl.openStream()) {
            scriptContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        String historicalVersionThreeContent;
        try (var inputStream = historicalVersionThreeUrl.openStream()) {
            historicalVersionThreeContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(historicalVersionThreeContent)
                .doesNotContain("idx_chat_message_conversation_user_role_created_id");
        assertThat(scriptContent).doesNotContain("idx_chat_message_conversation_user_role_created_id");
        assertThat(scriptContent).contains("ON DELETE CASCADE");
        assertThat(scriptContent).contains("ON DELETE SET NULL");

        String contextIndexContent;
        try (var inputStream = contextIndexMigrationUrl.openStream()) {
            contextIndexContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(contextIndexContent).contains("idx_chat_message_conversation_user_role_created_id");
    }
}

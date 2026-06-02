package com.agenthub.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

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
}

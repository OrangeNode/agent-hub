package com.agenthub.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:multimodal_image_summary_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class MultimodalImageSummaryMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateAttachmentSummaryAndImageModelColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                """, String.class);
        assertThat(tables).contains("chat_attachment", "chat_conversation_summary");

        List<String> modelColumns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'ai_model'
                """, String.class);
        assertThat(modelColumns).contains("image_input_supported", "image_token_estimate");
    }
}

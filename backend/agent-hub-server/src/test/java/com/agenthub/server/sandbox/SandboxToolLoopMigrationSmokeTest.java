package com.agenthub.server.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.sql.DataSource;
import com.agenthub.sandbox.artifact.mapper.SandboxArtifactMapper;
import com.agenthub.sandbox.execution.mapper.SandboxExecutionMapper;
import com.agenthub.sandbox.session.mapper.SandboxSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sandbox_tool_loop_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class SandboxToolLoopMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SandboxSessionMapper sandboxSessionMapper;

    @Autowired
    private SandboxExecutionMapper sandboxExecutionMapper;

    @Autowired
    private SandboxArtifactMapper sandboxArtifactMapper;

    @Test
    void shouldCreateSandboxTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tableNames = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('sandbox_session', 'sandbox_execution', 'sandbox_artifact')
                ORDER BY table_name
                """, String.class);

        assertThat(tableNames).containsExactly(
                "sandbox_artifact",
                "sandbox_execution",
                "sandbox_session"
        );
    }

    @Test
    void shouldCreateSandboxIndexesAndConstraints() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> constraints = jdbcTemplate.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema = 'public'
                    AND table_name IN ('sandbox_session', 'sandbox_execution', 'sandbox_artifact')
                """, String.class);

        assertThat(constraints).contains(
                "uk_sandbox_session_runtime_id",
                "fk_sandbox_session_conversation",
                "fk_sandbox_execution_session",
                "fk_sandbox_execution_conversation",
                "fk_sandbox_execution_message",
                "fk_sandbox_artifact_session",
                "fk_sandbox_artifact_execution",
                "fk_sandbox_artifact_conversation",
                "uk_sandbox_artifact_content",
                "uk_sandbox_artifact_object"
        );

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE LOWER(table_schema) = 'public'
                    AND LOWER(table_name) IN ('sandbox_session', 'sandbox_execution', 'sandbox_artifact')
                """, String.class);

        assertThat(indexes)
                .anyMatch(name -> name.equalsIgnoreCase("idx_sandbox_session_conversation"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_sandbox_execution_session"))
                .anyMatch(name -> name.equalsIgnoreCase("idx_sandbox_artifact_conversation_path"));
    }

    @Test
    void shouldRegisterSandboxMappers() {
        assertThat(sandboxSessionMapper).isNotNull();
        assertThat(sandboxExecutionMapper).isNotNull();
        assertThat(sandboxArtifactMapper).isNotNull();
    }

    @Test
    void shouldSupportConversationSessionExecutionAndArtifactRows() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO chat_conversation (user_id, title, status)
                VALUES (1, '沙箱测试会话', 'ACTIVE')
                """);
        Long conversationId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM chat_conversation
                WHERE title = '沙箱测试会话'
                """, Long.class);

        jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, user_id, role, content, status)
                VALUES (?, 1, 'assistant', 'tool call', 'COMPLETED')
                """, conversationId);
        Long messageId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM chat_message
                WHERE conversation_id = ?
                    AND content = 'tool call'
                """, Long.class, conversationId);

        jdbcTemplate.update("""
                INSERT INTO sandbox_session (conversation_id, user_id, sandbox_session_id, image)
                VALUES (?, 1, 'sbx_000001', 'minimal')
                """, conversationId);
        Long sandboxSessionId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM sandbox_session
                WHERE sandbox_session_id = 'sbx_000001'
                """, Long.class);

        jdbcTemplate.update("""
                INSERT INTO sandbox_execution (sandbox_session_id, conversation_id, message_id, tool_call_id, tool_name, command, status, exit_code, stdout_excerpt, duration_ms, completed_at)
                VALUES (?, ?, ?, 'call_1', 'exec', 'python main.py', 'COMPLETED', 0, 'ok', 120, CURRENT_TIMESTAMP)
                """, sandboxSessionId, conversationId, messageId);
        Long executionId = jdbcTemplate.queryForObject("""
                SELECT id
                FROM sandbox_execution
                WHERE tool_call_id = 'call_1'
                """, Long.class);

        jdbcTemplate.update("""
                INSERT INTO sandbox_artifact (sandbox_session_id, execution_id, conversation_id, relative_path, file_name, size_bytes, sha256, content_type, storage_bucket, storage_key, version)
                VALUES (?, ?, ?, 'reports/out.txt', 'out.txt', 2, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'text/plain', 'agent-hub-sandbox-artifacts', 'sandbox-artifacts/1/a/reports/out.txt', 1)
                """, sandboxSessionId, executionId, conversationId);

        Integer artifactCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM sandbox_artifact
                WHERE conversation_id = ?
                    AND relative_path = 'reports/out.txt'
                """, Integer.class, conversationId);

        assertThat(artifactCount).isEqualTo(1);
    }

    @Test
    void shouldNotRestartIdentityInMigration() throws IOException {
        var migrationScriptUrl = SandboxToolLoopMigrationSmokeTest.class
                .getResource("/db/migration/V7__init_sandbox_tool_loop.sql");

        assertThat(migrationScriptUrl).isNotNull();

        String scriptContent;
        try (var inputStream = migrationScriptUrl.openStream()) {
            scriptContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(scriptContent).doesNotContain("RESTART WITH");
    }
}

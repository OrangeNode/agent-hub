package com.agenthub.server.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_task_progress_migration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.data.redis.repositories.enabled=false"
})
class AgentTaskProgressMigrationSmokeTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldCreateTaskRunAndStepTables() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                    AND table_name IN ('agent_task_run', 'agent_task_step')
                ORDER BY table_name
                """, String.class);

        assertThat(tables).containsExactly("agent_task_run", "agent_task_step");
    }

    @Test
    void shouldCreateTaskRunColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'agent_task_run'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "id",
                "user_id",
                "conversation_id",
                "assistant_message_id",
                "parent_task_run_id",
                "mode",
                "status",
                "title",
                "plan_summary",
                "current_step_index",
                "total_steps",
                "revision",
                "error_message",
                "created_at",
                "updated_at",
                "completed_at"
        );
    }

    @Test
    void shouldCreateTaskStepColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> columns = jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'public'
                    AND table_name = 'agent_task_step'
                ORDER BY ordinal_position
                """, String.class);

        assertThat(columns).containsExactly(
                "id",
                "task_run_id",
                "step_index",
                "name",
                "status",
                "error_message",
                "started_at",
                "completed_at"
        );
    }

    @Test
    void shouldCreateTaskProgressIndexes() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT index_name
                FROM information_schema.indexes
                WHERE table_schema = 'public'
                    AND index_name IN (
                        'idx_agent_task_run_conversation_status',
                        'idx_agent_task_run_message',
                        'idx_agent_task_step_run_index'
                    )
                ORDER BY index_name
                """, String.class);

        assertThat(indexes).containsExactly(
                "idx_agent_task_run_conversation_status",
                "idx_agent_task_run_message",
                "idx_agent_task_step_run_index"
        );
    }

    @Test
    void shouldAllowTaskRunAndStepInsert() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        Long conversationId = createConversation(jdbcTemplate);
        Long assistantMessageId = createAssistantMessage(jdbcTemplate, conversationId);

        jdbcTemplate.update("""
                INSERT INTO agent_task_run (
                    user_id, conversation_id, assistant_message_id, mode, status,
                    title, plan_summary, current_step_index, total_steps, revision
                )
                VALUES (7, ?, ?, 'PLAN_EXECUTE', 'RUNNING',
                    '生成报告', '生成一份报告', 1, 2, 1)
                """, conversationId, assistantMessageId);
        Long taskRunId = jdbcTemplate.queryForObject("SELECT id FROM agent_task_run", Long.class);
        jdbcTemplate.update("""
                INSERT INTO agent_task_step (task_run_id, step_index, name, status)
                VALUES (?, 1, '分析需求', 'RUNNING')
                """, taskRunId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT run.mode, run.status, step.name, step.status AS step_status
                FROM agent_task_run run
                JOIN agent_task_step step ON step.task_run_id = run.id
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).containsEntry("mode", "PLAN_EXECUTE");
        assertThat(rows.getFirst()).containsEntry("status", "RUNNING");
        assertThat(rows.getFirst()).containsEntry("name", "分析需求");
        assertThat(rows.getFirst()).containsEntry("step_status", "RUNNING");
    }

    private Long createConversation(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("""
                INSERT INTO chat_conversation (user_id, title)
                VALUES (7, '任务会话')
                """);
        return jdbcTemplate.queryForObject("SELECT id FROM chat_conversation", Long.class);
    }

    private Long createAssistantMessage(JdbcTemplate jdbcTemplate, Long conversationId) {
        jdbcTemplate.update("""
                INSERT INTO chat_message (conversation_id, user_id, role, content)
                VALUES (?, 7, 'ASSISTANT', '处理中')
                """, conversationId);
        return jdbcTemplate.queryForObject("SELECT id FROM chat_message", Long.class);
    }
}

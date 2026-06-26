# 自动 Plan 与任务进度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现自动 Plan 路由、任务状态存储、SSE 任务进度事件，以及用户端输入框上方的任务进度展示。

**Architecture:** 后端在 `agent-hub-chat` 新增 `task` 子域，保存 `agent_task_run` 和 `agent_task_step` 当前状态；聊天流运行前由 `AgentRunRouter` 自动判断 `DIRECT_CHAT`、`TOOL_ASSISTED` 或 `PLAN_EXECUTE`，复杂任务创建任务进度并通过 SSE 推送。前端在 `user-web` 的 SSE parser 中接收任务进度事件，并用独立 `TaskProgressPanel` 展示折叠态和展开列表。

**Tech Stack:** Spring Boot 4、MyBatis-Plus、Flyway、JUnit 5、Mockito、Vue 3、TypeScript、Naive UI、Vitest。

---

## Scope Check

本计划跨后端状态、SSE 协议和前端展示，但它们都服务同一个用户可见能力：自动 Plan 任务进度。任务之间有明确顺序依赖，适合作为一个实施计划。

首版路由保持保守：只有明显复杂任务才进入 `PLAN_EXECUTE`，普通闲聊和普通写作不显示任务进度。这样可以避免当前聊天测试和普通体验被大范围扰动。

## File Structure

后端新增或修改：

- Create: `backend/agent-hub-server/src/main/resources/db/migration/V18__init_agent_task_progress.sql`
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/chat/AgentTaskProgressMigrationSmokeTest.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskRun.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskStep.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/mapper/AgentTaskRunMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/mapper/AgentTaskStepMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/dto/TaskProgressItem.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/dto/TaskStepItem.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/RunMode.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/ToolPolicy.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/PlanPolicy.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunDecision.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunInput.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunRouter.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentTaskPlan.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentTaskPlanner.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/AgentTaskService.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/impl/AgentTaskServiceImpl.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/controller/ChatTaskController.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`
- Modify: `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageProcessEvent.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentRunRouterTest.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentTaskPlannerTest.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/service/AgentTaskServiceImplTest.java`

前端新增或修改：

- Modify: `user-web/src/api/chat.ts`
- Create: `user-web/src/views/chat-task-progress.helpers.ts`
- Create: `user-web/src/views/TaskProgressPanel.vue`
- Modify: `user-web/src/views/ChatView.vue`
- Create: `user-web/tests/chat-task-progress.spec.ts`
- Modify: `user-web/tests/chat-stream-tool-events.spec.ts`

## Task 1: 数据库迁移与任务状态实体

**Files:**
- Create: `backend/agent-hub-server/src/test/java/com/agenthub/server/chat/AgentTaskProgressMigrationSmokeTest.java`
- Create: `backend/agent-hub-server/src/main/resources/db/migration/V18__init_agent_task_progress.sql`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskRun.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskStep.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/mapper/AgentTaskRunMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/mapper/AgentTaskStepMapper.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/dto/TaskProgressItem.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/dto/TaskStepItem.java`

- [ ] **Step 1: Write the failing migration smoke test**

Create `backend/agent-hub-server/src/test/java/com/agenthub/server/chat/AgentTaskProgressMigrationSmokeTest.java`:

```java
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

        jdbcTemplate.update("""
                INSERT INTO agent_task_run (
                    user_id, conversation_id, assistant_message_id, mode, status,
                    title, plan_summary, current_step_index, total_steps, revision
                )
                VALUES (7, 100, 200, 'PLAN_EXECUTE', 'RUNNING',
                    '生成报告', '生成一份报告', 1, 2, 1)
                """);
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
}
```

- [ ] **Step 2: Run migration test to verify it fails**

Run from `backend`:

```powershell
mvn -pl agent-hub-server -Dtest=AgentTaskProgressMigrationSmokeTest test
```

Expected: FAIL because `agent_task_run` and `agent_task_step` do not exist.

- [ ] **Step 3: Add migration**

Create `backend/agent-hub-server/src/main/resources/db/migration/V18__init_agent_task_progress.sql`:

```sql
CREATE TABLE agent_task_run (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    assistant_message_id BIGINT,
    parent_task_run_id BIGINT,
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    plan_summary TEXT,
    current_step_index INT NOT NULL DEFAULT 0,
    total_steps INT NOT NULL DEFAULT 0,
    revision INT NOT NULL DEFAULT 1,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_agent_task_run_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversation (id),
    CONSTRAINT fk_agent_task_run_message FOREIGN KEY (assistant_message_id) REFERENCES chat_message (id),
    CONSTRAINT fk_agent_task_run_parent FOREIGN KEY (parent_task_run_id) REFERENCES agent_task_run (id)
);

CREATE INDEX idx_agent_task_run_conversation_status
    ON agent_task_run (conversation_id, status, updated_at DESC, id DESC);

CREATE INDEX idx_agent_task_run_message
    ON agent_task_run (assistant_message_id);

CREATE TABLE agent_task_step (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    task_run_id BIGINT NOT NULL,
    step_index INT NOT NULL,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_agent_task_step_run FOREIGN KEY (task_run_id) REFERENCES agent_task_run (id),
    CONSTRAINT uk_agent_task_step_run_index UNIQUE (task_run_id, step_index)
);

CREATE INDEX idx_agent_task_step_run_index
    ON agent_task_step (task_run_id, step_index);
```

- [ ] **Step 4: Add entity, mapper and DTO classes**

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskRun.java`:

```java
package com.agenthub.chat.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("agent_task_run")
public class AgentTaskRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long conversationId;
    private Long assistantMessageId;
    private Long parentTaskRunId;
    private String mode;
    private String status;
    private String title;
    private String planSummary;
    private Integer currentStepIndex;
    private Integer totalSteps;
    private Integer revision;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }
    public Long getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(Long assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public Long getParentTaskRunId() { return parentTaskRunId; }
    public void setParentTaskRunId(Long parentTaskRunId) { this.parentTaskRunId = parentTaskRunId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getPlanSummary() { return planSummary; }
    public void setPlanSummary(String planSummary) { this.planSummary = planSummary; }
    public Integer getCurrentStepIndex() { return currentStepIndex; }
    public void setCurrentStepIndex(Integer currentStepIndex) { this.currentStepIndex = currentStepIndex; }
    public Integer getTotalSteps() { return totalSteps; }
    public void setTotalSteps(Integer totalSteps) { this.totalSteps = totalSteps; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
```

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/entity/AgentTaskStep.java`:

```java
package com.agenthub.chat.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.OffsetDateTime;

@TableName("agent_task_step")
public class AgentTaskStep {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskRunId;
    private Integer stepIndex;
    private String name;
    private String status;
    private String errorMessage;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskRunId() { return taskRunId; }
    public void setTaskRunId(Long taskRunId) { this.taskRunId = taskRunId; }
    public Integer getStepIndex() { return stepIndex; }
    public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
```

Create mappers:

```java
package com.agenthub.chat.task.mapper;

import com.agenthub.chat.task.entity.AgentTaskRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTaskRunMapper extends BaseMapper<AgentTaskRun> {
}
```

```java
package com.agenthub.chat.task.mapper;

import com.agenthub.chat.task.entity.AgentTaskStep;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentTaskStepMapper extends BaseMapper<AgentTaskStep> {
}
```

Create DTOs:

```java
package com.agenthub.chat.task.dto;

import java.util.List;

public record TaskProgressItem(
        Long taskRunId,
        String mode,
        String status,
        String title,
        String planSummary,
        Integer currentStepIndex,
        Integer totalSteps,
        Integer revision,
        String errorMessage,
        List<TaskStepItem> steps
) {
}
```

```java
package com.agenthub.chat.task.dto;

public record TaskStepItem(
        Integer stepIndex,
        String name,
        String status,
        String errorMessage
) {
}
```

- [ ] **Step 5: Run migration smoke test**

Run from `backend`:

```powershell
mvn -pl agent-hub-server -Dtest=AgentTaskProgressMigrationSmokeTest test
```

Expected: PASS.

- [ ] **Step 6: Commit task state schema**

```powershell
git add backend/agent-hub-server/src/main/resources/db/migration/V18__init_agent_task_progress.sql backend/agent-hub-server/src/test/java/com/agenthub/server/chat/AgentTaskProgressMigrationSmokeTest.java backend/agent-hub-chat/src/main/java/com/agenthub/chat/task
git commit -m "新增 Agent 任务进度状态表"
```

## Task 2: 任务服务、运行路由和轻量 Planner

**Files:**
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/service/AgentTaskServiceImplTest.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentRunRouterTest.java`
- Create: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentTaskPlannerTest.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/RunMode.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/ToolPolicy.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/PlanPolicy.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunDecision.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunInput.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunRouter.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentTaskPlan.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentTaskPlanner.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/AgentTaskService.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/impl/AgentTaskServiceImpl.java`

- [ ] **Step 1: Write failing router tests**

Create `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentRunRouterTest.java`:

```java
package com.agenthub.chat.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRunRouterTest {

    private final AgentRunRouter router = new AgentRunRouter();

    @Test
    void shouldRouteCasualChatToDirectChat() {
        AgentRunDecision decision = router.route(new AgentRunInput(
                "你好，今天心情不错",
                false,
                List.of(),
                false
        ));

        assertThat(decision.mode()).isEqualTo(RunMode.DIRECT_CHAT);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.NONE);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.NONE);
    }

    @Test
    void shouldRouteFileAndCodeTaskToPlanExecute() {
        AgentRunDecision decision = router.route(new AgentRunInput(
                "帮我修改后端代码，生成文件并运行测试",
                false,
                List.of(),
                true
        ));

        assertThat(decision.mode()).isEqualTo(RunMode.PLAN_EXECUTE);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.AUTO);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.AUTO);
        assertThat(decision.reason()).contains("复杂任务");
    }

    @Test
    void shouldRouteAttachmentAnalysisToPlanExecute() {
        AgentRunDecision decision = router.route(new AgentRunInput(
                "分析这个附件并输出报告",
                true,
                List.of(),
                false
        ));

        assertThat(decision.mode()).isEqualTo(RunMode.PLAN_EXECUTE);
    }

    @Test
    void shouldRouteSimpleToolNeedToToolAssisted() {
        AgentRunDecision decision = router.route(new AgentRunInput(
                "查一下这个 MCP 服务状态",
                false,
                List.of(),
                true
        ));

        assertThat(decision.mode()).isEqualTo(RunMode.TOOL_ASSISTED);
        assertThat(decision.toolPolicy()).isEqualTo(ToolPolicy.AUTO);
        assertThat(decision.planPolicy()).isEqualTo(PlanPolicy.NONE);
    }
}
```

- [ ] **Step 2: Write failing task service tests**

Create `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/service/AgentTaskServiceImplTest.java`:

```java
package com.agenthub.chat.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.entity.AgentTaskRun;
import com.agenthub.chat.task.entity.AgentTaskStep;
import com.agenthub.chat.task.mapper.AgentTaskRunMapper;
import com.agenthub.chat.task.mapper.AgentTaskStepMapper;
import com.agenthub.chat.task.runtime.AgentTaskPlan;
import com.agenthub.chat.task.runtime.RunMode;
import com.agenthub.chat.task.service.impl.AgentTaskServiceImpl;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentTaskServiceImplTest {

    @Test
    void shouldCreateRunAndSteps() {
        AgentTaskRunMapper runMapper = mock(AgentTaskRunMapper.class);
        AgentTaskStepMapper stepMapper = mock(AgentTaskStepMapper.class);
        AgentTaskService service = new AgentTaskServiceImpl(runMapper, stepMapper);
        when(runMapper.insert(any(AgentTaskRun.class))).thenAnswer(invocation -> {
            AgentTaskRun run = invocation.getArgument(0);
            run.setId(123L);
            return 1;
        });

        TaskProgressItem progress = service.createRun(
                7L,
                100L,
                200L,
                null,
                RunMode.PLAN_EXECUTE,
                new AgentTaskPlan("生成报告", "根据附件生成报告", List.of("分析需求", "读取材料", "整理回复"))
        );

        assertThat(progress.taskRunId()).isEqualTo(123L);
        assertThat(progress.status()).isEqualTo("PLANNING");
        assertThat(progress.currentStepIndex()).isZero();
        assertThat(progress.totalSteps()).isEqualTo(3);
        assertThat(progress.steps()).extracting("name")
                .containsExactly("分析需求", "读取材料", "整理回复");
        verify(runMapper).insert(any(AgentTaskRun.class));
        verify(stepMapper, org.mockito.Mockito.times(3)).insert(any(AgentTaskStep.class));
    }

    @Test
    void shouldMarkStepRunningAndCompleted() {
        AgentTaskRunMapper runMapper = mock(AgentTaskRunMapper.class);
        AgentTaskStepMapper stepMapper = mock(AgentTaskStepMapper.class);
        AgentTaskService service = new AgentTaskServiceImpl(runMapper, stepMapper);

        service.markStepRunning(123L, 2);
        service.markStepCompleted(123L, 2);

        ArgumentCaptor<AgentTaskRun> runCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        verify(runMapper, org.mockito.Mockito.atLeastOnce()).update(runCaptor.capture(), any());
        assertThat(runCaptor.getAllValues()).anySatisfy(run -> {
            assertThat(run.getStatus()).isEqualTo("RUNNING");
            assertThat(run.getCurrentStepIndex()).isEqualTo(2);
        });
        verify(stepMapper, org.mockito.Mockito.times(2)).update(any(AgentTaskStep.class), any());
    }
}
```

- [ ] **Step 3: Write failing planner tests**

Create `backend/agent-hub-chat/src/test/java/com/agenthub/chat/task/runtime/AgentTaskPlannerTest.java`:

```java
package com.agenthub.chat.task.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AgentTaskPlannerTest {

    private final AgentTaskPlanner planner = new AgentTaskPlanner();

    @Test
    void shouldParsePlannerJson() {
        LlmClient client = new FixedPlannerClient("""
                {"title":"生成报告","summary":"根据附件生成报告","steps":["分析需求","读取材料","生成报告","整理回复"]}
                """);

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "帮我分析附件并生成报告");

        assertThat(plan.title()).isEqualTo("生成报告");
        assertThat(plan.summary()).isEqualTo("根据附件生成报告");
        assertThat(plan.steps()).containsExactly("分析需求", "读取材料", "生成报告", "整理回复");
    }

    @Test
    void shouldFallbackWhenPlannerJsonIsInvalid() {
        LlmClient client = new FixedPlannerClient("不是 JSON");

        AgentTaskPlan plan = planner.plan(client, runtimeConfig(), "帮我修改代码并运行测试");

        assertThat(plan.title()).isEqualTo("执行复杂任务");
        assertThat(plan.steps()).containsExactly("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复");
    }

    private static AiModelRuntimeConfig runtimeConfig() {
        return new AiModelRuntimeConfig(
                10L,
                20L,
                "provider",
                "OpenAI Compatible",
                "OPENAI_COMPATIBLE",
                "https://api.example.com",
                "key",
                null,
                "model",
                4096,
                1024,
                true,
                true,
                1500
        );
    }

    private record FixedPlannerClient(String content) implements LlmClient {
        @Override
        public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> consumer) {
            consumer.accept(LlmStreamChunk.answer(content));
            consumer.accept(LlmStreamChunk.done());
        }
    }
}
```

- [ ] **Step 4: Run task tests to verify failure**

Run from `backend`:

```powershell
mvn -pl agent-hub-chat -Dtest=AgentRunRouterTest,AgentTaskServiceImplTest,AgentTaskPlannerTest test
```

Expected: FAIL because task runtime and service classes do not exist.

- [ ] **Step 5: Implement runtime enums and records**

Create these files under `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/`.

`RunMode.java`:

```java
package com.agenthub.chat.task.runtime;

public enum RunMode {
    DIRECT_CHAT,
    TOOL_ASSISTED,
    PLAN_EXECUTE
}
```

`ToolPolicy.java`:

```java
package com.agenthub.chat.task.runtime;

public enum ToolPolicy {
    NONE,
    AUTO,
    REQUIRED
}
```

`PlanPolicy.java`:

```java
package com.agenthub.chat.task.runtime;

public enum PlanPolicy {
    NONE,
    AUTO,
    FORCE
}
```

`AgentRunDecision.java`:

```java
package com.agenthub.chat.task.runtime;

public record AgentRunDecision(
        RunMode mode,
        ToolPolicy toolPolicy,
        PlanPolicy planPolicy,
        String reason
) {
}
```

`AgentRunInput.java`:

```java
package com.agenthub.chat.task.runtime;

import java.util.List;

public record AgentRunInput(
        String prompt,
        boolean hasAttachments,
        List<Long> skillIds,
        boolean hasAvailableTools
) {
    public AgentRunInput {
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
    }
}
```

`AgentTaskPlan.java`:

```java
package com.agenthub.chat.task.runtime;

import java.util.List;

public record AgentTaskPlan(
        String title,
        String summary,
        List<String> steps
) {
    public AgentTaskPlan {
        title = sanitize(title, "执行复杂任务");
        summary = sanitize(summary, title);
        steps = normalizeSteps(steps);
    }

    public static AgentTaskPlan fallback(String prompt) {
        return new AgentTaskPlan(
                "执行复杂任务",
                sanitize(prompt, "按用户要求执行复杂任务"),
                List.of("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复")
        );
    }

    private static List<String> normalizeSteps(List<String> values) {
        if (values == null) {
            return List.of("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复");
        }
        List<String> resolved = values.stream()
                .map(value -> sanitize(value, "执行任务"))
                .filter(value -> !value.isBlank())
                .distinct()
                .limit(6)
                .toList();
        if (resolved.size() < 3) {
            return List.of("分析需求", "准备上下文", "执行任务", "校验结果", "整理回复");
        }
        return resolved;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String stripped = value.strip();
        return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
    }
}
```

- [ ] **Step 6: Implement conservative router**

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentRunRouter.java`:

```java
package com.agenthub.chat.task.runtime;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class AgentRunRouter {

    public AgentRunDecision route(AgentRunInput input) {
        String prompt = input.prompt() == null ? "" : input.prompt().strip();
        String normalized = prompt.toLowerCase(Locale.ROOT);
        if (isPlanTask(normalized, input)) {
            return new AgentRunDecision(
                    RunMode.PLAN_EXECUTE,
                    ToolPolicy.AUTO,
                    PlanPolicy.AUTO,
                    "检测到复杂任务，自动进入 Plan 执行"
            );
        }
        if (input.hasAvailableTools() && isSimpleToolTask(normalized)) {
            return new AgentRunDecision(
                    RunMode.TOOL_ASSISTED,
                    ToolPolicy.AUTO,
                    PlanPolicy.NONE,
                    "检测到轻量工具需求"
            );
        }
        return new AgentRunDecision(
                RunMode.DIRECT_CHAT,
                ToolPolicy.NONE,
                PlanPolicy.NONE,
                "普通对话直接回复"
        );
    }

    private boolean isPlanTask(String prompt, AgentRunInput input) {
        if (input.hasAttachments() && containsAny(prompt, "分析", "总结", "报告", "导出", "生成")) {
            return true;
        }
        return containsAny(prompt,
                "修改代码", "修复", "运行测试", "生成文件", "下载", "导出",
                "数据分析", "处理数据", "读取文件", "写入文件", "创建项目",
                "实现功能", "改一下", "补充功能", "生成报告", "批量");
    }

    private boolean isSimpleToolTask(String prompt) {
        return containsAny(prompt, "查一下", "查询", "同步", "列出", "mcp", "状态");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 7: Implement lightweight LLM planner with fallback**

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/runtime/AgentTaskPlanner.java`:

```java
package com.agenthub.chat.task.runtime;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunkType;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentTaskPlanner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AgentTaskPlan plan(LlmClient client, AiModelRuntimeConfig runtimeConfig, String prompt) {
        if (client == null || runtimeConfig == null) {
            return AgentTaskPlan.fallback(prompt);
        }
        StringBuilder content = new StringBuilder();
        try {
            client.stream(new LlmStreamRequest(
                    runtimeConfig.baseUrl(),
                    runtimeConfig.apiKeyCipher(),
                    runtimeConfig.apiVersion(),
                    runtimeConfig.modelCode(),
                    plannerMessages(prompt),
                    runtimeConfig.maxOutputTokens(),
                    false,
                    List.of()
            ), chunk -> {
                if (chunk.type() == LlmStreamChunkType.ANSWER_DELTA && chunk.content() != null) {
                    content.append(chunk.content());
                }
            });
            return parsePlan(content.toString(), prompt);
        } catch (RuntimeException exception) {
            return AgentTaskPlan.fallback(prompt);
        }
    }

    private List<LlmMessage> plannerMessages(String prompt) {
        return List.of(
                new LlmMessage("system", """
                        你是任务规划器。只输出 JSON，不输出 Markdown。
                        JSON 格式：{"title":"短标题","summary":"一句话摘要","steps":["步骤1","步骤2","步骤3"]}
                        steps 必须是 3 到 6 个中文短步骤。
                        """),
                new LlmMessage("user", prompt == null ? "" : prompt)
        );
    }

    private AgentTaskPlan parsePlan(String raw, String prompt) {
        JsonNode root = OBJECT_MAPPER.readTree(extractJson(raw));
        List<String> steps = new ArrayList<>();
        JsonNode stepNodes = root.path("steps");
        if (stepNodes.isArray()) {
            stepNodes.forEach(node -> steps.add(node.asText("")));
        }
        return new AgentTaskPlan(
                root.path("title").asText("执行复杂任务"),
                root.path("summary").asText(prompt == null ? "按用户要求执行复杂任务" : prompt),
                steps
        );
    }

    private String extractJson(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }
}
```

- [ ] **Step 8: Implement task service**

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/AgentTaskService.java`:

```java
package com.agenthub.chat.task.service;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.runtime.AgentTaskPlan;
import com.agenthub.chat.task.runtime.RunMode;

public interface AgentTaskService {

    TaskProgressItem createRun(Long userId,
                               Long conversationId,
                               Long assistantMessageId,
                               Long parentTaskRunId,
                               RunMode mode,
                               AgentTaskPlan plan);

    TaskProgressItem replacePlan(Long taskRunId, AgentTaskPlan plan);

    TaskProgressItem markStepRunning(Long taskRunId, int stepIndex);

    TaskProgressItem markStepCompleted(Long taskRunId, int stepIndex);

    TaskProgressItem markFailed(Long taskRunId, String errorMessage);

    TaskProgressItem markCompleted(Long taskRunId);

    void stopByAssistantMessageId(Long assistantMessageId, String reason);

    TaskProgressItem findActiveProgress(Long userId, Long conversationId);
}
```

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/service/impl/AgentTaskServiceImpl.java` with these implementation rules:

```java
package com.agenthub.chat.task.service.impl;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.dto.TaskStepItem;
import com.agenthub.chat.task.entity.AgentTaskRun;
import com.agenthub.chat.task.entity.AgentTaskStep;
import com.agenthub.chat.task.mapper.AgentTaskRunMapper;
import com.agenthub.chat.task.mapper.AgentTaskStepMapper;
import com.agenthub.chat.task.runtime.AgentTaskPlan;
import com.agenthub.chat.task.runtime.RunMode;
import com.agenthub.chat.task.service.AgentTaskService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final String STATUS_PLANNING = "PLANNING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STEP_PENDING = "PENDING";
    private static final String STEP_RUNNING = "RUNNING";
    private static final String STEP_COMPLETED = "COMPLETED";

    private final AgentTaskRunMapper runMapper;
    private final AgentTaskStepMapper stepMapper;

    public AgentTaskServiceImpl(AgentTaskRunMapper runMapper, AgentTaskStepMapper stepMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    @Transactional
    public TaskProgressItem createRun(Long userId, Long conversationId, Long assistantMessageId,
                                      Long parentTaskRunId, RunMode mode, AgentTaskPlan plan) {
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setAssistantMessageId(assistantMessageId);
        run.setParentTaskRunId(parentTaskRunId);
        run.setMode(mode.name());
        run.setStatus(STATUS_PLANNING);
        run.setTitle(plan.title());
        run.setPlanSummary(plan.summary());
        run.setCurrentStepIndex(0);
        run.setTotalSteps(plan.steps().size());
        run.setRevision(1);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        insertSteps(run.getId(), plan.steps());
        return toProgress(run, listSteps(run.getId()));
    }

    @Override
    @Transactional
    public TaskProgressItem replacePlan(Long taskRunId, AgentTaskPlan plan) {
        stepMapper.delete(new LambdaQueryWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getTaskRunId, taskRunId));
        insertSteps(taskRunId, plan.steps());
        AgentTaskRun update = new AgentTaskRun();
        update.setTitle(plan.title());
        update.setPlanSummary(plan.summary());
        update.setTotalSteps(plan.steps().size());
        update.setUpdatedAt(OffsetDateTime.now());
        runMapper.update(update, new LambdaUpdateWrapper<AgentTaskRun>()
                .eq(AgentTaskRun::getId, taskRunId)
                .setSql("revision = revision + 1"));
        return progressById(taskRunId);
    }

    @Override
    public TaskProgressItem markStepRunning(Long taskRunId, int stepIndex) {
        updateRunStatus(taskRunId, STATUS_RUNNING, stepIndex, null, null);
        AgentTaskStep step = new AgentTaskStep();
        step.setStatus(STEP_RUNNING);
        step.setStartedAt(OffsetDateTime.now());
        stepMapper.update(step, stepWrapper(taskRunId, stepIndex));
        return progressById(taskRunId);
    }

    @Override
    public TaskProgressItem markStepCompleted(Long taskRunId, int stepIndex) {
        AgentTaskStep step = new AgentTaskStep();
        step.setStatus(STEP_COMPLETED);
        step.setCompletedAt(OffsetDateTime.now());
        stepMapper.update(step, stepWrapper(taskRunId, stepIndex));
        return progressById(taskRunId);
    }

    @Override
    public TaskProgressItem markFailed(Long taskRunId, String errorMessage) {
        updateRunStatus(taskRunId, STATUS_FAILED, null, errorMessage, OffsetDateTime.now());
        return progressById(taskRunId);
    }

    @Override
    public TaskProgressItem markCompleted(Long taskRunId) {
        updateRunStatus(taskRunId, STATUS_COMPLETED, null, null, OffsetDateTime.now());
        return progressById(taskRunId);
    }

    @Override
    public void stopByAssistantMessageId(Long assistantMessageId, String reason) {
        if (assistantMessageId == null) {
            return;
        }
        AgentTaskRun update = new AgentTaskRun();
        update.setStatus(STATUS_STOPPED);
        update.setErrorMessage(reason);
        update.setUpdatedAt(OffsetDateTime.now());
        update.setCompletedAt(OffsetDateTime.now());
        runMapper.update(update, new LambdaUpdateWrapper<AgentTaskRun>()
                .eq(AgentTaskRun::getAssistantMessageId, assistantMessageId)
                .in(AgentTaskRun::getStatus, STATUS_PLANNING, STATUS_RUNNING));
    }

    @Override
    public TaskProgressItem findActiveProgress(Long userId, Long conversationId) {
        AgentTaskRun run = runMapper.selectOne(new LambdaQueryWrapper<AgentTaskRun>()
                .eq(AgentTaskRun::getUserId, userId)
                .eq(AgentTaskRun::getConversationId, conversationId)
                .in(AgentTaskRun::getStatus, STATUS_PLANNING, STATUS_RUNNING)
                .orderByDesc(AgentTaskRun::getUpdatedAt)
                .orderByDesc(AgentTaskRun::getId)
                .last("LIMIT 1"));
        if (run == null) {
            return null;
        }
        return toProgress(run, listSteps(run.getId()));
    }

    private void insertSteps(Long taskRunId, List<String> steps) {
        for (int index = 0; index < steps.size(); index++) {
            AgentTaskStep step = new AgentTaskStep();
            step.setTaskRunId(taskRunId);
            step.setStepIndex(index + 1);
            step.setName(steps.get(index));
            step.setStatus(STEP_PENDING);
            stepMapper.insert(step);
        }
    }

    private void updateRunStatus(Long taskRunId, String status, Integer currentStepIndex,
                                 String errorMessage, OffsetDateTime completedAt) {
        AgentTaskRun update = new AgentTaskRun();
        update.setStatus(status);
        update.setCurrentStepIndex(currentStepIndex);
        update.setErrorMessage(errorMessage);
        update.setUpdatedAt(OffsetDateTime.now());
        update.setCompletedAt(completedAt);
        runMapper.update(update, new LambdaUpdateWrapper<AgentTaskRun>()
                .eq(AgentTaskRun::getId, taskRunId));
    }

    private LambdaUpdateWrapper<AgentTaskStep> stepWrapper(Long taskRunId, int stepIndex) {
        return new LambdaUpdateWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getTaskRunId, taskRunId)
                .eq(AgentTaskStep::getStepIndex, stepIndex);
    }

    private TaskProgressItem progressById(Long taskRunId) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        return toProgress(run, listSteps(taskRunId));
    }

    private List<AgentTaskStep> listSteps(Long taskRunId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AgentTaskStep>()
                .eq(AgentTaskStep::getTaskRunId, taskRunId)
                .orderByAsc(AgentTaskStep::getStepIndex));
    }

    private TaskProgressItem toProgress(AgentTaskRun run, List<AgentTaskStep> steps) {
        if (run == null) {
            return null;
        }
        return new TaskProgressItem(
                run.getId(),
                run.getMode(),
                run.getStatus(),
                run.getTitle(),
                run.getPlanSummary(),
                run.getCurrentStepIndex(),
                run.getTotalSteps(),
                run.getRevision(),
                run.getErrorMessage(),
                steps.stream()
                        .map(step -> new TaskStepItem(
                                step.getStepIndex(),
                                step.getName(),
                                step.getStatus(),
                                step.getErrorMessage()))
                        .toList()
        );
    }
}
```

- [ ] **Step 9: Run task runtime and service tests**

Run from `backend`:

```powershell
mvn -pl agent-hub-chat -Dtest=AgentRunRouterTest,AgentTaskServiceImplTest,AgentTaskPlannerTest test
```

Expected: PASS.

- [ ] **Step 10: Commit task runtime and service**

```powershell
git add backend/agent-hub-chat/src/main/java/com/agenthub/chat/task backend/agent-hub-chat/src/test/java/com/agenthub/chat/task
git commit -m "新增自动 Plan 路由和任务状态服务"
```

## Task 3: SSE 任务进度事件契约

**Files:**
- Modify: `backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`
- Modify: `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`

- [ ] **Step 1: Add failing SSE event tests**

Append to `SseEventSenderTest`:

```java
@Test
void eventTypeNamesIncludeTaskProgressEvents() {
    assertThat(SseEventType.TASK_PROGRESS_STARTED.eventName()).isEqualTo("task_progress_started");
    assertThat(SseEventType.TASK_PROGRESS_UPDATED.eventName()).isEqualTo("task_progress_updated");
    assertThat(SseEventType.TASK_STEP_STARTED.eventName()).isEqualTo("task_step_started");
    assertThat(SseEventType.TASK_STEP_COMPLETED.eventName()).isEqualTo("task_step_completed");
    assertThat(SseEventType.TASK_STEP_FAILED.eventName()).isEqualTo("task_step_failed");
    assertThat(SseEventType.TASK_PLAN_REVISED.eventName()).isEqualTo("task_plan_revised");
    assertThat(SseEventType.TASK_PROGRESS_COMPLETED.eventName()).isEqualTo("task_progress_completed");
}

@Test
void taskProgressDataCarriesCurrentStepAndSteps() {
    SseEventData.TaskProgress progress = new SseEventData.TaskProgress(
            123L,
            "PLAN_EXECUTE",
            "RUNNING",
            "生成报告",
            "根据附件生成报告",
            2,
            5,
            1,
            null,
            List.of(
                    new SseEventData.TaskStep(1, "分析需求", "COMPLETED", null),
                    new SseEventData.TaskStep(2, "读取材料", "RUNNING", null)
            )
    );

    assertThat(progress.taskRunId()).isEqualTo(123L);
    assertThat(progress.currentStepIndex()).isEqualTo(2);
    assertThat(progress.totalSteps()).isEqualTo(5);
    assertThat(progress.steps()).extracting(SseEventData.TaskStep::name)
            .containsExactly("分析需求", "读取材料");
}
```

Add `java.util.List` import if it is missing.

- [ ] **Step 2: Run SSE tests to verify failure**

Run from `backend`:

```powershell
mvn -pl agent-hub-sse test
```

Expected: FAIL because the task event enum values and records do not exist.

- [ ] **Step 3: Extend `SseEventType`**

Modify enum constants in `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java`:

```java
TOOL_RESULT("tool_result"),
TASK_PROGRESS_STARTED("task_progress_started"),
TASK_PROGRESS_UPDATED("task_progress_updated"),
TASK_STEP_STARTED("task_step_started"),
TASK_STEP_COMPLETED("task_step_completed"),
TASK_STEP_FAILED("task_step_failed"),
TASK_PLAN_REVISED("task_plan_revised"),
TASK_PROGRESS_COMPLETED("task_progress_completed"),
MESSAGE_COMPLETED("message_completed"),
```

- [ ] **Step 4: Extend `SseEventData`**

Modify the sealed permits list and add records in `backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java`:

```java
import java.util.List;
```

Add these types before `MessageCompleted`:

```java
record TaskProgress(Long taskRunId,
                    String mode,
                    String status,
                    String title,
                    String planSummary,
                    Integer currentStepIndex,
                    Integer totalSteps,
                    Integer revision,
                    String errorMessage,
                    List<TaskStep> steps) implements SseEventData {

    public TaskProgress {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}

record TaskStep(Integer stepIndex,
                String name,
                String status,
                String errorMessage) {
}
```

Update the sealed interface permits list to include `SseEventData.TaskProgress`.

- [ ] **Step 5: Run SSE tests**

Run from `backend`:

```powershell
mvn -pl agent-hub-sse test
```

Expected: PASS.

- [ ] **Step 6: Commit SSE task events**

```powershell
git add backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventType.java backend/agent-hub-sse/src/main/java/com/agenthub/sse/SseEventData.java backend/agent-hub-sse/src/test/java/com/agenthub/sse/SseEventSenderTest.java
git commit -m "扩展聊天 SSE 任务进度事件"
```

## Task 4: ChatService 接入自动 Plan 和任务进度

**Files:**
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageProcessEvent.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/impl/ChatServiceImpl.java`
- Modify: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/service/ChatService.java`
- Create: `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/controller/ChatTaskController.java`
- Modify: `backend/agent-hub-chat/src/test/java/com/agenthub/chat/service/ChatServiceImplTest.java`

- [ ] **Step 1: Add failing ChatService tests for Plan routing**

In `ChatServiceImplTest`, add a test that uses a complex prompt and verifies task events:

```java
@Test
void shouldCreateTaskProgressForPlanExecutePrompt() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = messageMapper();
    AiModelService modelService = mock(AiModelService.class);
    AttachmentService attachmentService = mock(AttachmentService.class);
    AgentTaskService taskService = mock(AgentTaskService.class);
    RecordingSseEventSender sender = new RecordingSseEventSender();
    RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
            LlmStreamChunk.answer("已完成"),
            LlmStreamChunk.done()
    ));
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender, attachmentService,
            llmClient, taskService, new AgentRunRouter(), new AgentTaskPlanner());
    when(modelService.requireDefaultRuntimeConfig()).thenReturn(runtimeConfig());
    when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
        ChatConversation conversation = invocation.getArgument(0);
        conversation.setId(100L);
        return 1;
    });
    when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
    when(attachmentService.resolveImagesForChat(7L, null, List.of(), List.of(), 1500)).thenReturn(List.of());
    TaskProgressItem created = new TaskProgressItem(
            123L, "PLAN_EXECUTE", "PLANNING", "执行复杂任务", "帮我修改代码并运行测试",
            0, 5, 1, null,
            List.of(new TaskStepItem(1, "分析需求", "PENDING", null))
    );
    when(taskService.createRun(any(), any(), any(), any(), eq(RunMode.PLAN_EXECUTE), any()))
            .thenReturn(created);
    when(taskService.markStepRunning(123L, 1)).thenReturn(new TaskProgressItem(
            123L, "PLAN_EXECUTE", "RUNNING", "执行复杂任务", "帮我修改代码并运行测试",
            1, 5, 1, null,
            List.of(new TaskStepItem(1, "分析需求", "RUNNING", null))
    ));
    when(taskService.markCompleted(123L)).thenReturn(new TaskProgressItem(
            123L, "PLAN_EXECUTE", "COMPLETED", "执行复杂任务", "帮我修改代码并运行测试",
            5, 5, 1, null,
            List.of(new TaskStepItem(1, "分析需求", "COMPLETED", null))
    ));

    service.stream(7L, null, null, null, "帮我修改后端代码，生成文件并运行测试",
            false, List.of(), List.of());

    verify(taskService).createRun(any(), eq(100L), eq(2L), any(), eq(RunMode.PLAN_EXECUTE), any());
    assertThat(sender.eventTypes()).contains(
            SseEventType.TASK_PROGRESS_STARTED,
            SseEventType.TASK_PROGRESS_UPDATED,
            SseEventType.TASK_PROGRESS_COMPLETED
    );
}
```

Add imports for task DTO/runtime/service.

- [ ] **Step 2: Add failing test that casual chat does not create task run**

In `ChatServiceImplTest`, add:

```java
@Test
void shouldNotCreateTaskProgressForDirectChat() {
    ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
    ChatMessageMapper messageMapper = messageMapper();
    AiModelService modelService = mock(AiModelService.class);
    AttachmentService attachmentService = mock(AttachmentService.class);
    AgentTaskService taskService = mock(AgentTaskService.class);
    RecordingSseEventSender sender = new RecordingSseEventSender();
    RecordingLlmClient llmClient = new RecordingLlmClient(List.of(
            LlmStreamChunk.answer("你好"),
            LlmStreamChunk.done()
    ));
    ChatServiceImpl service = service(conversationMapper, messageMapper, modelService, sender, attachmentService,
            llmClient, taskService, new AgentRunRouter(), new AgentTaskPlanner());
    when(modelService.requireDefaultRuntimeConfig()).thenReturn(runtimeConfig());
    when(conversationMapper.insert(any(ChatConversation.class))).thenAnswer(invocation -> {
        ChatConversation conversation = invocation.getArgument(0);
        conversation.setId(100L);
        return 1;
    });
    when(messageMapper.insert(any(ChatMessage.class))).thenAnswer(new SequentialMessageIdAnswer());
    when(attachmentService.resolveImagesForChat(7L, null, List.of(), List.of(), 1500)).thenReturn(List.of());

    service.stream(7L, null, null, null, "你好，介绍一下自己", false, List.of(), List.of());

    verify(taskService, never()).createRun(any(), any(), any(), any(), any(), any());
    assertThat(sender.eventTypes()).doesNotContain(SseEventType.TASK_PROGRESS_STARTED);
}
```

- [ ] **Step 3: Run ChatService tests to verify failure**

Run from `backend`:

```powershell
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest test
```

Expected: FAIL because `ChatServiceImpl` has not accepted task dependencies or emitted task events.

- [ ] **Step 4: Extend process event DTO for task revisions**

Modify `backend/agent-hub-chat/src/main/java/com/agenthub/chat/message/dto/MessageProcessEvent.java`:

```java
package com.agenthub.chat.message.dto;

public record MessageProcessEvent(
        String type,
        String content,
        ToolProcess tool,
        TaskProcess task
) {

    public MessageProcessEvent(String type, String content, ToolProcess tool) {
        this(type, content, tool, null);
    }

    public record ToolProcess(
            String toolCallId,
            String name,
            String status,
            String stdout,
            String stderr,
            String result
    ) {
    }

    public record TaskProcess(
            Long taskRunId,
            Integer revision,
            String summary
    ) {
    }
}
```

Existing `new MessageProcessEvent("tool", null, tool)` calls remain source-compatible through the overloaded constructor.

- [ ] **Step 5: Add task dependencies to `ChatServiceImpl`**

In `ChatServiceImpl`, add fields:

```java
private final AgentTaskService taskService;
private final AgentRunRouter runRouter;
private final AgentTaskPlanner taskPlanner;
```

Update the widest constructor to accept these dependencies after `ObjectStorageClient objectStorageClient`:

```java
AgentTaskService taskService,
AgentRunRouter runRouter,
AgentTaskPlanner taskPlanner
```

Assign defaults inside constructor:

```java
this.taskService = taskService;
this.runRouter = runRouter == null ? new AgentRunRouter() : runRouter;
this.taskPlanner = taskPlanner == null ? new AgentTaskPlanner() : taskPlanner;
```

Update Spring constructor to pass `taskService`, `runRouter`, and `taskPlanner`. Existing test constructors can pass `null` until their helper overloads are updated.

- [ ] **Step 6: Add task context records inside `ChatServiceImpl`**

Near existing private records, add:

```java
private record PreparedTask(
        AgentRunDecision decision,
        TaskProgressItem progress
) {
}
```

Extend `PreparedChat` to carry `PreparedTask task`. If `PreparedChat` currently has a shorter record signature, update all creation sites in `persistChatRecords` and all read sites.

- [ ] **Step 7: Route and create task run during persistence**

Inside chat preparation, compute route input after attachments are resolved:

```java
AgentRunDecision runDecision = runRouter.route(new AgentRunInput(
        normalizedPrompt,
        !images.isEmpty(),
        resolvedSkillIds,
        toolRegistry != null
));
```

After assistant message is inserted, create task run only for `PLAN_EXECUTE`:

```java
PreparedTask preparedTask = null;
if (runDecision.mode() == RunMode.PLAN_EXECUTE && taskService != null) {
    AgentTaskPlan fallbackPlan = AgentTaskPlan.fallback(normalizedPrompt);
    TaskProgressItem progress = taskService.createRun(
            userId,
            resolvedConversationId,
            assistantMessage.getId(),
            null,
            RunMode.PLAN_EXECUTE,
            fallbackPlan
    );
    preparedTask = new PreparedTask(runDecision, progress);
} else {
    preparedTask = new PreparedTask(runDecision, null);
}
```

Return `preparedTask` inside `PreparedChat`.

- [ ] **Step 8: Emit task progress during streaming**

In the async stream path, pass `preparedChat.task()` into `streamLlm`. At the start of `streamLlm`, after `meta`, `conversation`, and `message` events have been sent, send task progress if present:

```java
private void sendTaskProgress(SseEmitter emitter, SseEventType eventType, TaskProgressItem progress) {
    if (progress == null) {
        return;
    }
    send(emitter, eventType, new SseEventData.TaskProgress(
            progress.taskRunId(),
            progress.mode(),
            progress.status(),
            progress.title(),
            progress.planSummary(),
            progress.currentStepIndex(),
            progress.totalSteps(),
            progress.revision(),
            progress.errorMessage(),
            progress.steps().stream()
                    .map(step -> new SseEventData.TaskStep(
                            step.stepIndex(),
                            step.name(),
                            step.status(),
                            step.errorMessage()))
                    .toList()
    ));
}
```

Use these calls in `streamLlm`:

```java
TaskProgressItem taskProgress = preparedTask == null ? null : preparedTask.progress();
sendTaskProgress(emitter, SseEventType.TASK_PROGRESS_STARTED, taskProgress);
if (taskProgress != null && taskService != null) {
    taskProgress = taskService.markStepRunning(taskProgress.taskRunId(), 1);
    sendTaskProgress(emitter, SseEventType.TASK_PROGRESS_UPDATED, taskProgress);
}
```

When the assistant message completes successfully:

```java
if (taskProgress != null && taskService != null) {
    TaskProgressItem completed = taskService.markCompleted(taskProgress.taskRunId());
    sendTaskProgress(emitter, SseEventType.TASK_PROGRESS_COMPLETED, completed);
}
```

When failure path calls `failAssistantMessageAndSendError`, also call:

```java
if (taskProgress != null && taskService != null) {
    TaskProgressItem failed = taskService.markFailed(taskProgress.taskRunId(), exception.getMessage());
    sendTaskProgress(emitter, SseEventType.TASK_STEP_FAILED, failed);
}
```

- [ ] **Step 9: Revise plan using planner before main LLM execution**

At the beginning of `streamLlm`, after `TASK_PROGRESS_STARTED`, call planner for `PLAN_EXECUTE`:

```java
if (taskProgress != null && taskService != null && taskPlanner != null) {
    AgentTaskPlan revisedPlan = taskPlanner.plan(client, runtimeConfig, originalPrompt);
    TaskProgressItem revised = taskService.replacePlan(taskProgress.taskRunId(), revisedPlan);
    processRecorder.appendTaskRevision(revised.taskRunId(), revised.revision(), "已根据任务内容生成执行计划");
    sendTaskProgress(emitter, SseEventType.TASK_PLAN_REVISED, revised);
    taskProgress = revised;
}
```

Add `originalPrompt` as a `streamLlm` parameter. Add this method in `ProcessEventRecorder`:

```java
private void appendTaskRevision(Long taskRunId, Integer revision, String summary) {
    events.add(new MessageProcessEvent(
            "task_plan_revised",
            null,
            null,
            new MessageProcessEvent.TaskProcess(taskRunId, revision, summary)
    ));
}
```

- [ ] **Step 10: Stop active task when generation is stopped**

In `stopGeneration`, after the assistant message ownership/status check succeeds, add:

```java
if (taskService != null) {
    taskService.stopByAssistantMessageId(messageId, USER_STOPPED_GENERATION);
}
```

- [ ] **Step 11: Add active task query endpoint**

Create `backend/agent-hub-chat/src/main/java/com/agenthub/chat/task/controller/ChatTaskController.java`:

```java
package com.agenthub.chat.task.controller;

import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.service.AgentTaskService;
import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatTaskController {

    private final AgentTaskService taskService;

    public ChatTaskController(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/conversations/{conversationId}/tasks/active")
    public ApiResponse<TaskProgressItem> activeTask(Authentication authentication, @PathVariable Long conversationId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(taskService.findActiveProgress(loginUser.userId(), conversationId));
    }
}
```

- [ ] **Step 12: Run ChatService tests**

Run from `backend`:

```powershell
mvn -pl agent-hub-chat -Dtest=ChatServiceImplTest,AgentTaskServiceImplTest,AgentRunRouterTest,AgentTaskPlannerTest test
```

Expected: PASS.

- [ ] **Step 13: Commit backend ChatService integration**

```powershell
git add backend/agent-hub-chat/src/main/java/com/agenthub/chat backend/agent-hub-chat/src/test/java/com/agenthub/chat
git commit -m "接入自动 Plan 任务进度运行态"
```

## Task 5: 前端 SSE 类型、解析器和状态辅助函数

**Files:**
- Modify: `user-web/src/api/chat.ts`
- Create: `user-web/src/views/chat-task-progress.helpers.ts`
- Modify: `user-web/tests/chat-stream-tool-events.spec.ts`
- Create: `user-web/tests/chat-task-progress.spec.ts`

- [ ] **Step 1: Add failing SSE parser test**

Append to `user-web/tests/chat-stream-tool-events.spec.ts`:

```ts
it('应该分发任务进度事件', async () => {
  const events: unknown[] = []
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    body: sseBody(
      'event: task_progress_started\ndata: {"taskRunId":123,"mode":"PLAN_EXECUTE","status":"PLANNING","title":"生成报告","planSummary":"根据附件生成报告","currentStepIndex":0,"totalSteps":2,"revision":1,"errorMessage":null,"steps":[{"stepIndex":1,"name":"分析需求","status":"PENDING","errorMessage":null}]}\n\n',
      'event: task_progress_completed\ndata: {"taskRunId":123,"mode":"PLAN_EXECUTE","status":"COMPLETED","title":"生成报告","planSummary":"根据附件生成报告","currentStepIndex":2,"totalSteps":2,"revision":1,"errorMessage":null,"steps":[]}\n\n',
      'event: done\ndata: {}\n\n'
    )
  }))

  await new Promise<void>((resolve, reject) => {
    const timeout = window.setTimeout(() => reject(new Error('等待任务进度事件超时')), 1000)
    const finish = (callback: () => void) => {
      window.clearTimeout(timeout)
      callback()
    }
    openChatStream(
      { prompt: '生成报告', token: 'token' },
      {
        onTaskProgressStarted: (data) => events.push({ type: 'started', ...data }),
        onTaskProgressCompleted: (data) => events.push({ type: 'completed', ...data }),
        onDone: () => finish(resolve),
        onTransportError: (error) => finish(() => reject(error))
      }
    )
  })

  expect(events).toEqual([
    {
      type: 'started',
      taskRunId: 123,
      mode: 'PLAN_EXECUTE',
      status: 'PLANNING',
      title: '生成报告',
      planSummary: '根据附件生成报告',
      currentStepIndex: 0,
      totalSteps: 2,
      revision: 1,
      errorMessage: null,
      steps: [
        {
          stepIndex: 1,
          name: '分析需求',
          status: 'PENDING',
          errorMessage: null
        }
      ]
    },
    {
      type: 'completed',
      taskRunId: 123,
      mode: 'PLAN_EXECUTE',
      status: 'COMPLETED',
      title: '生成报告',
      planSummary: '根据附件生成报告',
      currentStepIndex: 2,
      totalSteps: 2,
      revision: 1,
      errorMessage: null,
      steps: []
    }
  ])
})
```

- [ ] **Step 2: Add failing helper tests**

Create `user-web/tests/chat-task-progress.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import {
  buildTaskProgressText,
  normalizeTaskProgress,
  taskStepStatusText
} from '../src/views/chat-task-progress.helpers'

describe('任务进度展示辅助函数', () => {
  it('应该生成折叠态文案', () => {
    expect(buildTaskProgressText({
      taskRunId: 123,
      mode: 'PLAN_EXECUTE',
      status: 'RUNNING',
      title: '生成报告',
      planSummary: '',
      currentStepIndex: 2,
      totalSteps: 5,
      revision: 1,
      errorMessage: null,
      steps: []
    })).toBe('任务进度：正在执行 2 / 5')
  })

  it('应该归一化任务步骤状态', () => {
    const progress = normalizeTaskProgress({
      taskRunId: 123,
      mode: 'PLAN_EXECUTE',
      status: 'RUNNING',
      title: '生成报告',
      planSummary: null,
      currentStepIndex: 2,
      totalSteps: 5,
      revision: 1,
      errorMessage: null,
      steps: [
        { stepIndex: 1, name: '分析需求', status: 'COMPLETED', errorMessage: null },
        { stepIndex: 2, name: '读取材料', status: 'RUNNING', errorMessage: null }
      ]
    })

    expect(progress.steps.map((step) => step.status)).toEqual(['COMPLETED', 'RUNNING'])
    expect(taskStepStatusText('RUNNING')).toBe('进行中')
    expect(taskStepStatusText('COMPLETED')).toBe('已完成')
  })
})
```

- [ ] **Step 3: Run frontend tests to verify failure**

Run from `user-web`:

```powershell
npm run test -- chat-stream-tool-events.spec.ts chat-task-progress.spec.ts
```

Expected: FAIL because task event types and helper functions do not exist.

- [ ] **Step 4: Extend `chat.ts` types and handlers**

In `user-web/src/api/chat.ts`, add:

```ts
export type ChatTaskStatus = 'PLANNING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'STOPPED' | string
export type ChatTaskStepStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED' | string

export interface ChatTaskStepRecord {
  stepIndex: number
  name: string
  status: ChatTaskStepStatus
  errorMessage?: string | null
}

export interface ChatTaskProgressRecord {
  taskRunId: number
  mode: string
  status: ChatTaskStatus
  title: string
  planSummary?: string | null
  currentStepIndex: number
  totalSteps: number
  revision: number
  errorMessage?: string | null
  steps: ChatTaskStepRecord[]
}
```

Extend `ChatStreamHandlers`:

```ts
onTaskProgressStarted?: (data: ChatTaskProgressRecord) => void
onTaskProgressUpdated?: (data: ChatTaskProgressRecord) => void
onTaskStepStarted?: (data: ChatTaskProgressRecord) => void
onTaskStepCompleted?: (data: ChatTaskProgressRecord) => void
onTaskStepFailed?: (data: ChatTaskProgressRecord) => void
onTaskPlanRevised?: (data: ChatTaskProgressRecord) => void
onTaskProgressCompleted?: (data: ChatTaskProgressRecord) => void
```

Add dispatch cases:

```ts
case 'task_progress_started':
  dispatchJson(event.data, handlers.onTaskProgressStarted)
  break
case 'task_progress_updated':
  dispatchJson(event.data, handlers.onTaskProgressUpdated)
  break
case 'task_step_started':
  dispatchJson(event.data, handlers.onTaskStepStarted)
  break
case 'task_step_completed':
  dispatchJson(event.data, handlers.onTaskStepCompleted)
  break
case 'task_step_failed':
  dispatchJson(event.data, handlers.onTaskStepFailed)
  break
case 'task_plan_revised':
  dispatchJson(event.data, handlers.onTaskPlanRevised)
  break
case 'task_progress_completed':
  dispatchJson(event.data, handlers.onTaskProgressCompleted)
  break
```

Add active task API:

```ts
export async function fetchActiveTaskProgress(conversationId: number) {
  const response = await http.get<ApiResponse<ChatTaskProgressRecord | null>>(
    `/chat/conversations/${conversationId}/tasks/active`
  )
  return response.data.data
}
```

- [ ] **Step 5: Add task progress helper**

Create `user-web/src/views/chat-task-progress.helpers.ts`:

```ts
import type { ChatTaskProgressRecord, ChatTaskStepRecord, ChatTaskStepStatus } from '../api/chat'

export interface NormalizedTaskStep extends ChatTaskStepRecord {
  stepIndex: number
  name: string
  status: ChatTaskStepStatus
}

export interface NormalizedTaskProgress extends ChatTaskProgressRecord {
  steps: NormalizedTaskStep[]
}

export function normalizeTaskProgress(progress: ChatTaskProgressRecord): NormalizedTaskProgress {
  return {
    ...progress,
    title: progress.title || '任务进度',
    planSummary: progress.planSummary ?? '',
    currentStepIndex: Number(progress.currentStepIndex ?? 0),
    totalSteps: Number(progress.totalSteps ?? progress.steps?.length ?? 0),
    revision: Number(progress.revision ?? 1),
    steps: [...(progress.steps ?? [])]
      .map((step, index) => ({
        stepIndex: Number(step.stepIndex ?? index + 1),
        name: step.name || `任务 ${index + 1}`,
        status: step.status || 'PENDING',
        errorMessage: step.errorMessage ?? null
      }))
      .sort((a, b) => a.stepIndex - b.stepIndex)
  }
}

export function buildTaskProgressText(progress: ChatTaskProgressRecord | null | undefined) {
  if (!progress) {
    return ''
  }
  const current = Math.max(0, Number(progress.currentStepIndex ?? 0))
  const total = Math.max(0, Number(progress.totalSteps ?? progress.steps?.length ?? 0))
  if (progress.status === 'COMPLETED') {
    return `任务进度：已完成 ${total} / ${total}`
  }
  if (progress.status === 'FAILED') {
    return `任务进度：执行失败 ${current} / ${total}`
  }
  if (progress.status === 'STOPPED') {
    return `任务进度：已停止 ${current} / ${total}`
  }
  if (progress.status === 'PLANNING') {
    return '任务进度：准备中'
  }
  return `任务进度：正在执行 ${current} / ${total}`
}

export function taskStepStatusText(status: ChatTaskStepStatus) {
  switch (status) {
    case 'RUNNING':
      return '进行中'
    case 'COMPLETED':
      return '已完成'
    case 'FAILED':
      return '失败'
    case 'SKIPPED':
      return '已跳过'
    default:
      return '等待中'
  }
}
```

- [ ] **Step 6: Run frontend parser/helper tests**

Run from `user-web`:

```powershell
npm run test -- chat-stream-tool-events.spec.ts chat-task-progress.spec.ts
```

Expected: PASS.

- [ ] **Step 7: Commit frontend parser and helpers**

```powershell
git add user-web/src/api/chat.ts user-web/src/views/chat-task-progress.helpers.ts user-web/tests/chat-stream-tool-events.spec.ts user-web/tests/chat-task-progress.spec.ts
git commit -m "新增前端任务进度事件解析"
```

## Task 6: 前端任务进度面板与 ChatView 接入

**Files:**
- Create: `user-web/src/views/TaskProgressPanel.vue`
- Modify: `user-web/src/views/ChatView.vue`
- Create: `user-web/tests/chat-task-progress-layout.spec.ts`

- [ ] **Step 1: Add failing layout test**

Create `user-web/tests/chat-task-progress-layout.spec.ts`:

```ts
import { describe, expect, it } from 'vitest'
import taskProgressPanelSource from '../src/views/TaskProgressPanel.vue?raw'
import chatViewSource from '../src/views/ChatView.vue?raw'

describe('任务进度面板布局', () => {
  it('应该在聊天页接入任务进度面板', () => {
    expect(chatViewSource).toContain('TaskProgressPanel')
    expect(chatViewSource).toContain('activeTaskProgress')
    expect(chatViewSource).toContain('onTaskProgressStarted')
    expect(chatViewSource).toContain('onTaskProgressCompleted')
  })

  it('折叠态应该显示任务进度文案并可展开列表', () => {
    expect(taskProgressPanelSource).toContain('{{ summaryText }}')
    expect(taskProgressPanelSource).toContain('任务名称')
    expect(taskProgressPanelSource).toContain('任务进度')
    expect(taskProgressPanelSource).toContain('task-progress-spinner')
    expect(taskProgressPanelSource).toContain('task-progress-dot completed')
  })
})
```

- [ ] **Step 2: Run layout test to verify failure**

Run from `user-web`:

```powershell
npm run test -- chat-task-progress-layout.spec.ts
```

Expected: FAIL because the component and ChatView integration do not exist.

- [ ] **Step 3: Create `TaskProgressPanel.vue`**

Create `user-web/src/views/TaskProgressPanel.vue`:

```vue
<template>
  <section class="task-progress-panel" :class="{ expanded }" aria-label="任务进度">
    <button
      type="button"
      class="task-progress-summary"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span>{{ summaryText }}</span>
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path d="m6 9 6 6 6-6" />
      </svg>
    </button>

    <Transition name="task-progress-expand">
      <div v-if="expanded" class="task-progress-detail">
        <div class="task-progress-row heading">
          <span>任务名称</span>
          <span>任务进度</span>
        </div>
        <div
          v-for="step in normalized.steps"
          :key="step.stepIndex"
          class="task-progress-row"
        >
          <span class="task-progress-name">{{ step.name }}</span>
          <span class="task-progress-state">
            <span v-if="step.status === 'RUNNING'" class="task-progress-spinner" aria-hidden="true" />
            <span v-else class="task-progress-dot" :class="dotClass(step.status)" aria-hidden="true" />
            {{ taskStepStatusText(step.status) }}
          </span>
        </div>
      </div>
    </Transition>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatTaskProgressRecord, ChatTaskStepStatus } from '../api/chat'
import {
  buildTaskProgressText,
  normalizeTaskProgress,
  taskStepStatusText
} from './chat-task-progress.helpers'

const props = defineProps<{
  progress: ChatTaskProgressRecord
}>()

const expanded = ref(false)
const normalized = computed(() => normalizeTaskProgress(props.progress))
const summaryText = computed(() => buildTaskProgressText(normalized.value))

function dotClass(status: ChatTaskStepStatus) {
  if (status === 'COMPLETED') {
    return 'completed'
  }
  if (status === 'FAILED') {
    return 'failed'
  }
  if (status === 'SKIPPED') {
    return 'skipped'
  }
  return 'pending'
}
</script>

<style scoped>
.task-progress-panel {
  width: min(760px, calc(100vw - 48px));
  margin: 0 auto 10px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
  color: #111827;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
}

.task-progress-summary {
  display: flex;
  width: 100%;
  min-height: 40px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.task-progress-summary svg {
  width: 16px;
  height: 16px;
  flex: 0 0 auto;
  fill: none;
  stroke: currentColor;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 1.8;
  transition: transform 0.16s ease;
}

.task-progress-panel.expanded .task-progress-summary svg {
  transform: rotate(180deg);
}

.task-progress-detail {
  display: grid;
  gap: 2px;
  padding: 0 12px 10px;
}

.task-progress-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 112px;
  align-items: center;
  gap: 12px;
  min-height: 30px;
  font-size: 13px;
}

.task-progress-row.heading {
  color: #6b7280;
  font-size: 12px;
}

.task-progress-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-progress-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.task-progress-spinner {
  width: 10px;
  height: 10px;
  border: 2px solid #bfdbfe;
  border-top-color: #2563eb;
  border-radius: 999px;
  animation: task-progress-spin 0.75s linear infinite;
}

.task-progress-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #d1d5db;
}

.task-progress-dot.completed {
  background: #16a34a;
}

.task-progress-dot.failed {
  background: #dc2626;
}

.task-progress-dot.skipped {
  background: #d97706;
}

.task-progress-expand-enter-active,
.task-progress-expand-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.task-progress-expand-enter-from,
.task-progress-expand-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@keyframes task-progress-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 640px) {
  .task-progress-panel {
    width: calc(100vw - 24px);
  }

  .task-progress-row {
    grid-template-columns: minmax(0, 1fr) 92px;
  }
}
</style>
```

- [ ] **Step 4: Integrate `TaskProgressPanel` in `ChatView.vue`**

Modify imports:

```ts
import TaskProgressPanel from './TaskProgressPanel.vue'
import { normalizeTaskProgress } from './chat-task-progress.helpers'
import type { ChatTaskProgressRecord } from '../api/chat'
```

Add state near other refs:

```ts
const activeTaskProgress = ref<ChatTaskProgressRecord | null>(null)
```

Add helper:

```ts
function applyTaskProgress(progress: ChatTaskProgressRecord) {
  activeTaskProgress.value = normalizeTaskProgress(progress)
}
```

Reset stale task progress when sending a new message:

```ts
activeTaskProgress.value = null
```

Place it immediately before both composer blocks:

```vue
<TaskProgressPanel
  v-if="activeTaskProgress"
  :progress="activeTaskProgress"
/>
```

For the empty state, place it above `<div class="landing-composer" ...>`. For the conversation view, place it inside `<footer class="conversation-composer">` before pending images.

Add stream handlers:

```ts
onTaskProgressStarted: applyTaskProgress,
onTaskProgressUpdated: applyTaskProgress,
onTaskStepStarted: applyTaskProgress,
onTaskStepCompleted: applyTaskProgress,
onTaskStepFailed: applyTaskProgress,
onTaskPlanRevised: applyTaskProgress,
onTaskProgressCompleted: applyTaskProgress,
```

- [ ] **Step 5: Recover active task when switching conversations**

Import `fetchActiveTaskProgress` from `../api/chat`.

After conversation messages load successfully in the active conversation refresh path, call:

```ts
async function refreshActiveTaskProgress() {
  if (!activeConversationId.value) {
    activeTaskProgress.value = null
    return
  }
  const progress = await fetchActiveTaskProgress(activeConversationId.value)
  activeTaskProgress.value = progress ? normalizeTaskProgress(progress) : null
}
```

Call `void refreshActiveTaskProgress()` after selecting a conversation and after `refreshActiveConversationMessages()` completes.

- [ ] **Step 6: Run frontend tests**

Run from `user-web`:

```powershell
npm run test -- chat-task-progress-layout.spec.ts chat-task-progress.spec.ts chat-stream-tool-events.spec.ts
npm run type-check
```

Expected: PASS.

- [ ] **Step 7: Commit task progress panel**

```powershell
git add user-web/src/views/TaskProgressPanel.vue user-web/src/views/ChatView.vue user-web/tests/chat-task-progress-layout.spec.ts
git commit -m "新增用户端任务进度展示"
```

## Task 7: End-to-End Verification

**Files:**
- Verify backend modules
- Verify user-web

- [ ] **Step 1: Run backend focused tests**

Run from `backend`:

```powershell
mvn -pl agent-hub-server -Dtest=AgentTaskProgressMigrationSmokeTest test
mvn -pl agent-hub-sse test
mvn -pl agent-hub-chat -Dtest=AgentRunRouterTest,AgentTaskPlannerTest,AgentTaskServiceImplTest,ChatServiceImplTest test
```

Expected: all PASS.

- [ ] **Step 2: Run full backend tests if focused tests pass**

Run from `backend`:

```powershell
mvn test
```

Expected: PASS.

- [ ] **Step 3: Run user-web tests and build**

Run from `user-web`:

```powershell
npm run test
npm run build
```

Expected: PASS.

- [ ] **Step 4: Inspect final git diff**

Run from repo root:

```powershell
git status --short
git diff --check
```

Expected: no unexpected files; `git diff --check` has no whitespace errors.

- [ ] **Step 5: Commit verification fixes if any were needed**

If verification required fixes, commit only those files:

```powershell
git add <fixed-files>
git commit -m "修复自动 Plan 任务进度验证问题"
```

If no fixes were needed, do not create an empty commit.

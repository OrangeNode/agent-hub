package com.agenthub.chat.task.runtime;

import static com.agenthub.chat.message.ChatMessageStatus.COMPLETED;
import static com.agenthub.chat.message.ChatMessageStatus.FAILED;
import static com.agenthub.chat.message.ChatMessageStatus.STOPPED;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmClientFactory;
import com.agenthub.ai.model.dto.AiModelRuntimeConfig;
import com.agenthub.chat.message.entity.ChatMessage;
import com.agenthub.chat.run.RunEventSink;
import com.agenthub.chat.task.dto.TaskProgressItem;
import com.agenthub.chat.task.dto.TaskStepItem;
import com.agenthub.chat.task.service.AgentTaskService;
import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventType;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 把 PLAN_EXECUTE 模式的任务进度同步到数据库并推送给前端。
 * 进度只是展示能力，任何失败都不能影响聊天主链路。
 */
@Component
public class ChatTaskProgressTracker {

    private final AgentTaskService taskService;
    private final AgentTaskPlanner taskPlanner;
    private final Function<String, LlmClient> llmClientFactory;

    @Autowired
    public ChatTaskProgressTracker(AgentTaskService taskService, AgentTaskPlanner taskPlanner) {
        this(taskService, taskPlanner, LlmClientFactory::create);
    }

    public ChatTaskProgressTracker(
            AgentTaskService taskService,
            AgentTaskPlanner taskPlanner,
            Function<String, LlmClient> llmClientFactory
    ) {
        this.taskService = taskService;
        this.taskPlanner = taskPlanner;
        this.llmClientFactory = llmClientFactory;
    }

    public TaskRunContext createIfNeeded(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            String prompt,
            AgentRunDecision runDecision
    ) {
        if (taskService == null || runDecision == null || runDecision.runMode() != RunMode.PLAN_EXECUTE) {
            return null;
        }
        try {
            TaskProgressItem progress = taskService.createRun(
                    userId,
                    conversationId,
                    assistantMessageId,
                    runDecision.runMode(),
                    prompt,
                    prompt,
                    List.of("正在生成任务计划")
            );
            return new TaskRunContext(progress);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    public void start(RunEventSink events, AiModelRuntimeConfig runtimeConfig, String prompt, TaskRunContext context) {
        if (context == null || context.progressDisabled() || context.taskRunId() == null || taskService == null) {
            return;
        }
        try {
            send(events, SseEventType.TASK_PROGRESS_STARTED, context.initialProgress());
            TaskProgressItem latest;
            if (taskPlanner != null) {
                latest = taskService.replacePlan(context.taskRunId(), plan(runtimeConfig, prompt));
                send(events, SseEventType.TASK_PLAN_REVISED, latest);
            }
            latest = taskService.markStepRunning(context.taskRunId(), 1);
            send(events, SseEventType.TASK_PROGRESS_UPDATED, latest);
        } catch (RuntimeException exception) {
            context.disableProgress();
        }
    }

    public void finish(RunEventSink events, ChatMessage assistantMessage, TaskRunContext context) {
        if (context == null || context.taskRunId() == null || taskService == null) {
            return;
        }
        try {
            if (COMPLETED.equals(assistantMessage.getStatus())) {
                send(events, SseEventType.TASK_PROGRESS_COMPLETED, taskService.markCompleted(context.taskRunId()));
                return;
            }
            if (FAILED.equals(assistantMessage.getStatus())) {
                TaskProgressItem failed = taskService.markFailed(context.taskRunId(), assistantMessage.getErrorMessage());
                send(events, SseEventType.TASK_STEP_FAILED, failed);
                send(events, SseEventType.TASK_PROGRESS_UPDATED, failed);
                return;
            }
            if (STOPPED.equals(assistantMessage.getStatus())) {
                stopQuietly(assistantMessage.getUserId(), assistantMessage.getId());
            }
        } catch (RuntimeException exception) {
            // 已发送原始聊天错误事件时，不让任务状态同步错误覆盖原始流结束流程。
        }
    }

    public void stopQuietly(Long userId, Long assistantMessageId, TaskRunContext context) {
        if (context == null || context.taskRunId() == null) {
            return;
        }
        stopQuietly(userId, assistantMessageId);
    }

    public void stopQuietly(Long userId, Long assistantMessageId) {
        if (taskService == null) {
            return;
        }
        try {
            taskService.stopByAssistantMessageId(userId, assistantMessageId);
        } catch (RuntimeException exception) {
            // 只做状态兜底，不影响调用方的停止处理。
        }
    }

    private AgentTaskPlan plan(AiModelRuntimeConfig runtimeConfig, String prompt) {
        try {
            AgentTaskPlan plan = taskPlanner.plan(llmClientFactory.apply(runtimeConfig.protocolType()), runtimeConfig, prompt);
            if (plan != null) {
                return plan;
            }
        } catch (RuntimeException exception) {
            // Planner 只负责优化任务展示；失败时回退默认计划，避免中断聊天主流程。
        }
        return new AgentTaskPlan(prompt, prompt, List.of("分析需求", "执行任务", "整理回复"));
    }

    private void send(RunEventSink events, SseEventType eventType, TaskProgressItem progress) {
        if (progress == null) {
            return;
        }
        events.send(eventType, new SseEventData.TaskProgress(
                progress.taskRunId(),
                progress.mode(),
                progress.status(),
                progress.title(),
                progress.planSummary(),
                progress.currentStepIndex(),
                progress.totalSteps(),
                progress.revision(),
                progress.errorMessage(),
                progress.steps() == null ? List.of() : progress.steps().stream()
                        .map(ChatTaskProgressTracker::toSseTaskStep)
                        .toList()
        ));
    }

    private static SseEventData.TaskStep toSseTaskStep(TaskStepItem step) {
        return new SseEventData.TaskStep(step.stepIndex(), step.name(), step.status(), step.errorMessage());
    }
}

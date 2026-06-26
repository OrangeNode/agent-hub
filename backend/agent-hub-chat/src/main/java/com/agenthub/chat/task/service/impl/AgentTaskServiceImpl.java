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
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    private final AgentTaskRunMapper runMapper;
    private final AgentTaskStepMapper stepMapper;

    public AgentTaskServiceImpl(AgentTaskRunMapper runMapper, AgentTaskStepMapper stepMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    @Override
    @Transactional
    public TaskProgressItem createRun(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            RunMode mode,
            String title,
            String planSummary,
            List<String> steps
    ) {
        List<String> normalizedSteps = normalizeSteps(steps);
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskRun run = new AgentTaskRun();
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setAssistantMessageId(assistantMessageId);
        run.setMode(mode.name());
        run.setStatus("PLANNING");
        run.setTitle(defaultText(title, "任务进度"));
        run.setPlanSummary(defaultText(planSummary, title));
        run.setCurrentStepIndex(0);
        run.setTotalSteps(normalizedSteps.size());
        run.setRevision(1);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);
        List<AgentTaskStep> insertedSteps = insertSteps(run.getId(), normalizedSteps);
        return toProgress(run, insertedSteps);
    }

    @Override
    @Transactional
    public TaskProgressItem replacePlan(Long taskRunId, AgentTaskPlan plan) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        List<String> steps = normalizeSteps(plan.steps());
        OffsetDateTime now = OffsetDateTime.now();
        stepMapper.delete(new QueryWrapper<AgentTaskStep>().eq("task_run_id", taskRunId));
        run.setTitle(defaultText(plan.title(), run.getTitle()));
        run.setPlanSummary(defaultText(plan.summary(), run.getPlanSummary()));
        run.setCurrentStepIndex(0);
        run.setTotalSteps(steps.size());
        run.setRevision(safeInt(run.getRevision(), 1) + 1);
        run.setStatus("PLANNING");
        run.setErrorMessage(null);
        run.setUpdatedAt(now);
        run.setCompletedAt(null);
        runMapper.updateById(run);
        List<AgentTaskStep> insertedSteps = insertSteps(taskRunId, steps);
        return toProgress(run, insertedSteps);
    }

    @Override
    @Transactional
    public TaskProgressItem markStepRunning(Long taskRunId, Integer stepIndex) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskStep step = selectStep(taskRunId, stepIndex);
        step.setStatus("RUNNING");
        step.setStartedAt(now);
        step.setErrorMessage(null);
        stepMapper.updateById(step);
        run.setStatus("RUNNING");
        run.setCurrentStepIndex(stepIndex);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
        return toProgress(run, listSteps(taskRunId));
    }

    @Override
    @Transactional
    public TaskProgressItem markStepCompleted(Long taskRunId, Integer stepIndex) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskStep step = selectStep(taskRunId, stepIndex);
        step.setStatus("COMPLETED");
        step.setCompletedAt(now);
        step.setErrorMessage(null);
        stepMapper.updateById(step);
        run.setStatus("RUNNING");
        run.setCurrentStepIndex(stepIndex);
        run.setUpdatedAt(now);
        runMapper.updateById(run);
        return toProgress(run, listSteps(taskRunId));
    }

    @Override
    @Transactional
    public TaskProgressItem markFailed(Long taskRunId, String errorMessage) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        OffsetDateTime now = OffsetDateTime.now();
        run.setStatus("FAILED");
        run.setErrorMessage(errorMessage);
        run.setUpdatedAt(now);
        run.setCompletedAt(now);
        runMapper.updateById(run);
        return toProgress(run, listSteps(taskRunId));
    }

    @Override
    @Transactional
    public TaskProgressItem markCompleted(Long taskRunId) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        OffsetDateTime now = OffsetDateTime.now();
        run.setStatus("COMPLETED");
        run.setCurrentStepIndex(safeInt(run.getTotalSteps(), 0));
        run.setUpdatedAt(now);
        run.setCompletedAt(now);
        runMapper.updateById(run);
        return toProgress(run, listSteps(taskRunId));
    }

    @Override
    @Transactional
    public void stopByAssistantMessageId(Long userId, Long assistantMessageId) {
        AgentTaskRun update = new AgentTaskRun();
        OffsetDateTime now = OffsetDateTime.now();
        update.setStatus("STOPPED");
        update.setUpdatedAt(now);
        update.setCompletedAt(now);
        runMapper.update(update, new UpdateWrapper<AgentTaskRun>()
                .eq("user_id", userId)
                .eq("assistant_message_id", assistantMessageId)
                .in("status", List.of("PLANNING", "RUNNING")));
    }

    @Override
    public TaskProgressItem findActiveProgress(Long userId, Long conversationId) {
        List<AgentTaskRun> runs = runMapper.selectList(new QueryWrapper<AgentTaskRun>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .in("status", List.of("PLANNING", "RUNNING"))
                .orderByDesc("updated_at")
                .orderByDesc("id"));
        if (runs.isEmpty()) {
            return null;
        }
        AgentTaskRun run = runs.getFirst();
        return toProgress(run, listSteps(run.getId()));
    }

    private AgentTaskStep selectStep(Long taskRunId, Integer stepIndex) {
        return stepMapper.selectOne(new QueryWrapper<AgentTaskStep>()
                .eq("task_run_id", taskRunId)
                .eq("step_index", stepIndex));
    }

    private List<AgentTaskStep> insertSteps(Long taskRunId, List<String> steps) {
        List<AgentTaskStep> insertedSteps = new java.util.ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            AgentTaskStep step = new AgentTaskStep();
            step.setTaskRunId(taskRunId);
            step.setStepIndex(i + 1);
            step.setName(steps.get(i));
            step.setStatus("PENDING");
            stepMapper.insert(step);
            insertedSteps.add(step);
        }
        return insertedSteps;
    }

    private List<AgentTaskStep> listSteps(Long taskRunId) {
        return stepMapper.selectList(new QueryWrapper<AgentTaskStep>()
                        .eq("task_run_id", taskRunId)
                        .orderByAsc("step_index"))
                .stream()
                .sorted(Comparator.comparing(AgentTaskStep::getStepIndex))
                .toList();
    }

    private TaskProgressItem toProgress(AgentTaskRun run, List<AgentTaskStep> steps) {
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
                                step.getErrorMessage()
                        ))
                        .toList()
        );
    }

    private List<String> normalizeSteps(List<String> steps) {
        List<String> normalized = steps == null ? List.of() : steps.stream()
                .map(step -> step == null ? "" : step.trim())
                .filter(step -> !step.isEmpty())
                .toList();
        return normalized.isEmpty() ? List.of("执行任务") : normalized;
    }

    private String defaultText(String value, String fallback) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.trim();
        }
        return "任务进度";
    }

    private Integer safeInt(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }
}

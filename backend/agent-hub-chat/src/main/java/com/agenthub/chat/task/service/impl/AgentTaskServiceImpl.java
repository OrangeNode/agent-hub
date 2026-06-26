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
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentTaskServiceImpl implements AgentTaskService {

    private static final List<String> ACTIVE_STATUSES = List.of("PLANNING", "RUNNING");

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
        if (mode == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "运行模式不能为空");
        }
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
        if (plan == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务计划不能为空");
        }
        AgentTaskRun run = requireRun(taskRunId);
        requireActiveRun(run);
        List<String> steps = normalizeSteps(plan.steps());
        OffsetDateTime now = OffsetDateTime.now();

        AgentTaskRun update = new AgentTaskRun();
        update.setTitle(defaultText(plan.title(), run.getTitle()));
        update.setPlanSummary(defaultText(plan.summary(), run.getPlanSummary()));
        update.setCurrentStepIndex(0);
        update.setTotalSteps(steps.size());
        update.setStatus("PLANNING");
        update.setUpdatedAt(now);
        int affectedRows = runMapper.update(update, new UpdateWrapper<AgentTaskRun>()
                .eq("id", taskRunId)
                .eq("revision", safeInt(run.getRevision(), 1))
                .in("status", ACTIVE_STATUSES)
                .set("error_message", null)
                .set("completed_at", null)
                .setSql("revision = revision + 1"));
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务计划已变更，请刷新后重试");
        }

        stepMapper.delete(new QueryWrapper<AgentTaskStep>().eq("task_run_id", taskRunId));
        insertSteps(taskRunId, steps);
        return snapshot(taskRunId);
    }

    @Override
    @Transactional
    public TaskProgressItem markStepRunning(Long taskRunId, Integer stepIndex) {
        AgentTaskRun run = requireActiveRun(requireRun(taskRunId));
        AgentTaskStep step = selectStep(taskRunId, stepIndex);
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskStep stepUpdate = new AgentTaskStep();
        stepUpdate.setStatus("RUNNING");
        stepUpdate.setStartedAt(now);
        stepUpdate.setErrorMessage(null);
        updateStep(taskRunId, stepIndex, step, stepUpdate);

        AgentTaskRun update = new AgentTaskRun();
        update.setStatus("RUNNING");
        update.setCurrentStepIndex(stepIndex);
        update.setUpdatedAt(now);
        updateActiveRun(run, update);
        return snapshot(taskRunId);
    }

    @Override
    @Transactional
    public TaskProgressItem markStepCompleted(Long taskRunId, Integer stepIndex) {
        AgentTaskRun run = requireActiveRun(requireRun(taskRunId));
        AgentTaskStep step = selectStep(taskRunId, stepIndex);
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskStep stepUpdate = new AgentTaskStep();
        stepUpdate.setStatus("COMPLETED");
        stepUpdate.setCompletedAt(now);
        stepUpdate.setErrorMessage(null);
        updateStep(taskRunId, stepIndex, step, stepUpdate);

        AgentTaskRun update = new AgentTaskRun();
        update.setStatus("RUNNING");
        update.setCurrentStepIndex(stepIndex);
        update.setUpdatedAt(now);
        updateActiveRun(run, update);
        return snapshot(taskRunId);
    }

    @Override
    @Transactional
    public TaskProgressItem markFailed(Long taskRunId, String errorMessage) {
        AgentTaskRun run = requireActiveRun(requireRun(taskRunId));
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskRun update = new AgentTaskRun();
        update.setStatus("FAILED");
        update.setErrorMessage(errorMessage);
        update.setUpdatedAt(now);
        update.setCompletedAt(now);
        updateActiveRun(run.getId(), update);
        return snapshot(taskRunId);
    }

    @Override
    @Transactional
    public TaskProgressItem markCompleted(Long taskRunId) {
        AgentTaskRun run = requireActiveRun(requireRun(taskRunId));
        OffsetDateTime now = OffsetDateTime.now();
        AgentTaskRun update = new AgentTaskRun();
        update.setStatus("COMPLETED");
        update.setCurrentStepIndex(safeInt(run.getTotalSteps(), 0));
        update.setUpdatedAt(now);
        update.setCompletedAt(now);
        updateActiveRun(run.getId(), update);
        return snapshot(taskRunId);
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
                .in("status", ACTIVE_STATUSES));
    }

    @Override
    public TaskProgressItem findActiveProgress(Long userId, Long conversationId) {
        List<AgentTaskRun> runs = runMapper.selectList(new QueryWrapper<AgentTaskRun>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId)
                .in("status", ACTIVE_STATUSES)
                .orderByDesc("updated_at")
                .orderByDesc("id"));
        if (runs.isEmpty()) {
            return null;
        }
        AgentTaskRun run = runs.getFirst();
        return toProgress(run, listSteps(run.getId()));
    }

    private TaskProgressItem snapshot(Long taskRunId) {
        AgentTaskRun refreshed = requireRun(taskRunId);
        return toProgress(refreshed, listSteps(taskRunId));
    }

    private AgentTaskRun requireRun(Long taskRunId) {
        AgentTaskRun run = runMapper.selectById(taskRunId);
        if (run == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return run;
    }

    private AgentTaskRun requireActiveRun(AgentTaskRun run) {
        if (!ACTIVE_STATUSES.contains(run.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务已结束，不能继续更新");
        }
        return run;
    }

    private void updateActiveRun(Long taskRunId, AgentTaskRun update) {
        int affectedRows = runMapper.update(update, new UpdateWrapper<AgentTaskRun>()
                .eq("id", taskRunId)
                .in("status", ACTIVE_STATUSES));
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务已结束，不能继续更新");
        }
    }

    private void updateActiveRun(AgentTaskRun run, AgentTaskRun update) {
        int affectedRows = runMapper.update(update, new UpdateWrapper<AgentTaskRun>()
                .eq("id", run.getId())
                .eq("revision", safeInt(run.getRevision(), 1))
                .in("status", ACTIVE_STATUSES));
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务计划已变更，请刷新后重试");
        }
    }

    private void updateStep(Long taskRunId, Integer stepIndex, AgentTaskStep step, AgentTaskStep update) {
        int affectedRows = stepMapper.update(update, new UpdateWrapper<AgentTaskStep>()
                .eq("id", step.getId())
                .eq("task_run_id", taskRunId)
                .eq("step_index", stepIndex));
        if (affectedRows == 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "任务计划已变更，请刷新后重试");
        }
    }

    private AgentTaskStep selectStep(Long taskRunId, Integer stepIndex) {
        AgentTaskStep step = stepMapper.selectOne(new QueryWrapper<AgentTaskStep>()
                .eq("task_run_id", taskRunId)
                .eq("step_index", stepIndex));
        if (step == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务步骤不存在");
        }
        return step;
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

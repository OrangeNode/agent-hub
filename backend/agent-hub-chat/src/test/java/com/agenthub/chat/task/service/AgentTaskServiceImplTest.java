package com.agenthub.chat.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class AgentTaskServiceImplTest {

    private AgentTaskRunMapper runMapper;
    private AgentTaskStepMapper stepMapper;
    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        runMapper = mock(AgentTaskRunMapper.class);
        stepMapper = mock(AgentTaskStepMapper.class);
        service = new AgentTaskServiceImpl(runMapper, stepMapper);
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(1);
        when(stepMapper.update(any(AgentTaskStep.class), any(Wrapper.class))).thenReturn(1);
        doAnswer(invocation -> {
            AgentTaskRun run = invocation.getArgument(0);
            run.setId(100L);
            return 1;
        }).when(runMapper).insert(any(AgentTaskRun.class));
        doAnswer(invocation -> {
            AgentTaskStep step = invocation.getArgument(0);
            step.setId((long) step.getStepIndex());
            return 1;
        }).when(stepMapper).insert(any(AgentTaskStep.class));
    }

    @Test
    void shouldCreateRunAndPendingSteps() {
        TaskProgressItem progress = service.createRun(
                7L,
                20L,
                30L,
                RunMode.PLAN_EXECUTE,
                "生成报告",
                "分析并生成报告",
                List.of("分析需求", "生成报告")
        );

        ArgumentCaptor<AgentTaskRun> runCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        verify(runMapper).insert(runCaptor.capture());
        AgentTaskRun insertedRun = runCaptor.getValue();
        assertThat(insertedRun.getUserId()).isEqualTo(7L);
        assertThat(insertedRun.getConversationId()).isEqualTo(20L);
        assertThat(insertedRun.getAssistantMessageId()).isEqualTo(30L);
        assertThat(insertedRun.getMode()).isEqualTo("PLAN_EXECUTE");
        assertThat(insertedRun.getStatus()).isEqualTo("PLANNING");
        assertThat(insertedRun.getCurrentStepIndex()).isZero();
        assertThat(insertedRun.getTotalSteps()).isEqualTo(2);
        assertThat(insertedRun.getRevision()).isEqualTo(1);

        ArgumentCaptor<AgentTaskStep> stepCaptor = ArgumentCaptor.forClass(AgentTaskStep.class);
        verify(stepMapper, org.mockito.Mockito.times(2)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).extracting(AgentTaskStep::getName)
                .containsExactly("分析需求", "生成报告");
        assertThat(stepCaptor.getAllValues()).extracting(AgentTaskStep::getStatus)
                .containsExactly("PENDING", "PENDING");
        assertThat(progress.status()).isEqualTo("PLANNING");
        assertThat(progress.steps()).hasSize(2);
    }

    @Test
    void shouldRejectCreateRunWhenModeIsNull() {
        assertThatThrownBy(() -> service.createRun(7L, 20L, 30L, null, "任务", "计划", List.of("步骤")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("运行模式不能为空")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldReplacePlanWithRevisionAndActiveStatusCondition() {
        AgentTaskRun existing = run(100L, "PLANNING", 1, 2);
        AgentTaskRun updated = run(100L, "PLANNING", 2, 3);
        updated.setTitle("新任务");
        updated.setPlanSummary("新计划");
        when(runMapper.selectById(100L)).thenReturn(existing, updated);
        when(stepMapper.selectList(any())).thenReturn(steps("第一步", "第二步", "第三步"));

        TaskProgressItem progress = service.replacePlan(
                100L,
                new AgentTaskPlan("新任务", "新计划", List.of("第一步", "第二步", "第三步"))
        );

        ArgumentCaptor<AgentTaskRun> updateCaptor = ArgumentCaptor.forClass(AgentTaskRun.class);
        ArgumentCaptor<Wrapper<AgentTaskRun>> wrapperCaptor = wrapperCaptor();
        verify(runMapper).update(updateCaptor.capture(), wrapperCaptor.capture());
        assertThat(updateCaptor.getValue().getRevision()).isNull();
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains("revision = revision + 1");
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .contains("id", "revision", "status");
        verify(stepMapper).delete(any(Wrapper.class));
        ArgumentCaptor<AgentTaskStep> stepCaptor = ArgumentCaptor.forClass(AgentTaskStep.class);
        verify(stepMapper, org.mockito.Mockito.times(3)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).extracting(AgentTaskStep::getName)
                .containsExactly("第一步", "第二步", "第三步");
        assertThat(progress.revision()).isEqualTo(2);
        assertThat(progress.steps()).extracting("name").containsExactly("第一步", "第二步", "第三步");
    }

    @Test
    void shouldRejectReplacePlanWhenConcurrentRevisionChanged() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2));
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.replacePlan(
                100L,
                new AgentTaskPlan("新任务", "新计划", List.of("第一步", "第二步"))
        ))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划已变更，请刷新后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(stepMapper, never()).delete(any(Wrapper.class));
        verify(stepMapper, never()).insert(any(AgentTaskStep.class));
    }

    @Test
    void shouldRejectReplacePlanWhenPlanIsNull() {
        assertThatThrownBy(() -> service.replacePlan(100L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划不能为空")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectTerminalRunWhenMarkingCompleted() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "STOPPED", 1, 2));

        assertThatThrownBy(() -> service.markCompleted(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务已结束，不能继续更新")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(runMapper, never()).update(any(AgentTaskRun.class), any(Wrapper.class));
    }

    @Test
    void shouldRejectTerminalRunWhenMarkingStepCompleted() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "COMPLETED", 1, 2));

        assertThatThrownBy(() -> service.markStepCompleted(100L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务已结束，不能继续更新")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        verify(stepMapper, never()).updateById(any(AgentTaskStep.class));
    }

    @Test
    void shouldRejectMissingTaskRun() {
        when(runMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.markFailed(404L, "失败"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRejectMissingStep() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 1, 2));
        when(stepMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.markStepRunning(100L, 9))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务步骤不存在")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void shouldRejectStepRunningWhenStepUpdateLostRaceAndNotUpdateRun() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2));
        when(stepMapper.selectOne(any())).thenReturn(step(1, "分析需求", "PENDING"));
        when(stepMapper.update(any(AgentTaskStep.class), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.markStepRunning(100L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划已变更，请刷新后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
        InOrder inOrder = org.mockito.Mockito.inOrder(runMapper, stepMapper);
        inOrder.verify(runMapper).update(any(AgentTaskRun.class), any(Wrapper.class));
        inOrder.verify(stepMapper).update(any(AgentTaskStep.class), any(Wrapper.class));
    }

    @Test
    void shouldUpdateRunBeforeStepWhenMarkingStepRunning() {
        AgentTaskStep first = step(1, "分析需求", "PENDING");
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2), run(100L, "RUNNING", 3, 2));
        when(stepMapper.selectOne(any())).thenReturn(first);
        when(stepMapper.selectList(any())).thenReturn(List.of(first));

        service.markStepRunning(100L, 1);

        InOrder inOrder = org.mockito.Mockito.inOrder(stepMapper, runMapper);
        inOrder.verify(stepMapper).selectOne(any(Wrapper.class));
        inOrder.verify(runMapper).update(any(AgentTaskRun.class), any(Wrapper.class));
        inOrder.verify(stepMapper).update(any(AgentTaskStep.class), any(Wrapper.class));
    }

    @Test
    void shouldRejectStepCompletedWhenRunRevisionChanged() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2));
        when(stepMapper.selectOne(any())).thenReturn(step(1, "分析需求", "RUNNING"));
        when(stepMapper.update(any(AgentTaskStep.class), any(Wrapper.class))).thenReturn(1);
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.markStepCompleted(100L, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划已变更，请刷新后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectCompletedWhenRunRevisionChanged() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2));
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.markCompleted(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划已变更，请刷新后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldRejectFailedWhenRunRevisionChanged() {
        when(runMapper.selectById(100L)).thenReturn(run(100L, "RUNNING", 3, 2));
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.markFailed(100L, "执行失败"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("任务计划已变更，请刷新后重试")
                .extracting("errorCode")
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void shouldMarkIncompleteStepsCompletedWhenTaskCompleted() {
        AgentTaskRun runningRun = run(100L, "RUNNING", 1, 2);
        AgentTaskRun completedRun = run(100L, "COMPLETED", 1, 2);
        completedRun.setCurrentStepIndex(2);
        when(runMapper.selectById(100L)).thenReturn(runningRun, completedRun);
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(1, "分析需求", "COMPLETED"),
                step(2, "生成报告", "COMPLETED")
        ));

        TaskProgressItem completed = service.markCompleted(100L);

        ArgumentCaptor<AgentTaskStep> stepCaptor = ArgumentCaptor.forClass(AgentTaskStep.class);
        verify(stepMapper).update(stepCaptor.capture(), any(Wrapper.class));
        assertThat(stepCaptor.getValue().getStatus()).isEqualTo("COMPLETED");
        assertThat(stepCaptor.getValue().getCompletedAt()).isNotNull();
        assertThat(completed.steps()).extracting("status").containsExactly("COMPLETED", "COMPLETED");
    }

    @Test
    void shouldMarkIncompleteStepsFailedWhenTaskFailed() {
        AgentTaskRun runningRun = run(100L, "RUNNING", 1, 2);
        AgentTaskRun failedRun = run(100L, "FAILED", 1, 2);
        failedRun.setErrorMessage("执行失败");
        when(runMapper.selectById(100L)).thenReturn(runningRun, failedRun);
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(1, "分析需求", "FAILED"),
                step(2, "生成报告", "FAILED")
        ));

        TaskProgressItem failed = service.markFailed(100L, "执行失败");

        ArgumentCaptor<AgentTaskStep> stepCaptor = ArgumentCaptor.forClass(AgentTaskStep.class);
        verify(stepMapper).update(stepCaptor.capture(), any(Wrapper.class));
        assertThat(stepCaptor.getValue().getStatus()).isEqualTo("FAILED");
        assertThat(stepCaptor.getValue().getErrorMessage()).isEqualTo("执行失败");
        assertThat(stepCaptor.getValue().getCompletedAt()).isNotNull();
        assertThat(failed.steps()).extracting("status").containsExactly("FAILED", "FAILED");
    }

    @Test
    void shouldMarkIncompleteStepsStoppedWhenTaskStopped() {
        when(runMapper.selectList(any())).thenReturn(List.of(run(100L, "RUNNING", 1, 2)));

        service.stopByAssistantMessageId(7L, 30L);

        ArgumentCaptor<AgentTaskStep> stepCaptor = ArgumentCaptor.forClass(AgentTaskStep.class);
        verify(stepMapper).update(stepCaptor.capture(), any(Wrapper.class));
        assertThat(stepCaptor.getValue().getStatus()).isEqualTo("STOPPED");
        assertThat(stepCaptor.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void shouldNotMarkStepsStoppedWhenStopRunCasFails() {
        when(runMapper.selectList(any())).thenReturn(List.of(run(100L, "RUNNING", 1, 2)));
        when(runMapper.update(any(AgentTaskRun.class), any(Wrapper.class))).thenReturn(0);

        service.stopByAssistantMessageId(7L, 30L);

        verify(stepMapper, never()).update(any(AgentTaskStep.class), any(Wrapper.class));
    }

    @Test
    void shouldMarkStepRunningCompletedFailedCompletedAndStopped() {
        AgentTaskRun runningRun = run(100L, "RUNNING", 1, 2);
        AgentTaskStep first = step(1, "分析需求", "PENDING");
        AgentTaskStep second = step(2, "生成报告", "PENDING");
        AgentTaskRun completedRun = run(100L, "COMPLETED", 1, 2);
        completedRun.setCurrentStepIndex(2);
        AgentTaskRun failedRun = run(100L, "FAILED", 1, 2);
        failedRun.setErrorMessage("执行失败");
        when(runMapper.selectById(100L)).thenReturn(
                run(100L, "PLANNING", 1, 2),
                runningRun,
                runningRun,
                runningRun,
                runningRun,
                failedRun,
                runningRun,
                completedRun
        );
        when(stepMapper.selectOne(any())).thenReturn(first, first);
        when(stepMapper.selectList(any())).thenReturn(List.of(first, second));
        when(runMapper.selectList(any())).thenReturn(List.of(runningRun));

        TaskProgressItem running = service.markStepRunning(100L, 1);
        TaskProgressItem completedStep = service.markStepCompleted(100L, 1);
        TaskProgressItem failed = service.markFailed(100L, "执行失败");
        TaskProgressItem completed = service.markCompleted(100L);
        service.stopByAssistantMessageId(7L, 30L);

        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(completedStep.currentStepIndex()).isEqualTo(0);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.errorMessage()).isEqualTo("执行失败");
        assertThat(completed.status()).isEqualTo("COMPLETED");
        verify(runMapper, org.mockito.Mockito.atLeast(5)).update(any(AgentTaskRun.class), any(Wrapper.class));
        InOrder inOrder = org.mockito.Mockito.inOrder(runMapper, stepMapper);
        inOrder.verify(runMapper).update(any(AgentTaskRun.class), any(Wrapper.class));
        inOrder.verify(stepMapper).update(any(AgentTaskStep.class), any(Wrapper.class));
    }

    @Test
    void shouldFindLatestActiveProgressForUserAndConversation() {
        AgentTaskRun active = run(100L, "RUNNING", 2, 3);
        active.setUserId(7L);
        active.setConversationId(20L);
        when(runMapper.selectList(any())).thenReturn(List.of(active));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(1, "分析需求", "COMPLETED"),
                step(2, "执行任务", "RUNNING")
        ));

        TaskProgressItem progress = service.findActiveProgress(7L, 20L);

        assertThat(progress.taskRunId()).isEqualTo(100L);
        assertThat(progress.status()).isEqualTo("RUNNING");
        assertThat(progress.steps()).extracting("name").containsExactly("分析需求", "执行任务");
        verify(runMapper).selectList(any(Wrapper.class));
    }

    @Test
    void shouldFindLatestProgressIncludingTerminalStatusForUserAndConversation() {
        AgentTaskRun completed = run(101L, "COMPLETED", 4, 2);
        completed.setCurrentStepIndex(2);
        when(runMapper.selectList(any())).thenReturn(List.of(completed));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                step(1, "collect context", "COMPLETED"),
                step(2, "write answer", "COMPLETED")
        ));

        TaskProgressItem progress = service.findLatestProgress(7L, 20L);

        assertThat(progress.taskRunId()).isEqualTo(101L);
        assertThat(progress.status()).isEqualTo("COMPLETED");
        assertThat(progress.currentStepIndex()).isEqualTo(2);
        assertThat(progress.steps()).extracting("status").containsExactly("COMPLETED", "COMPLETED");
        ArgumentCaptor<Wrapper<AgentTaskRun>> wrapperCaptor = wrapperCaptor();
        verify(runMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
                .contains("user_id", "conversation_id")
                .doesNotContain("status");
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Wrapper<AgentTaskRun>> wrapperCaptor() {
        return ArgumentCaptor.forClass(Wrapper.class);
    }

    private AgentTaskRun run(Long id, String status, Integer revision, Integer totalSteps) {
        AgentTaskRun run = new AgentTaskRun();
        run.setId(id);
        run.setUserId(7L);
        run.setConversationId(20L);
        run.setAssistantMessageId(30L);
        run.setMode("PLAN_EXECUTE");
        run.setStatus(status);
        run.setTitle("生成报告");
        run.setPlanSummary("分析并生成报告");
        run.setCurrentStepIndex(0);
        run.setTotalSteps(totalSteps);
        run.setRevision(revision);
        return run;
    }

    private List<AgentTaskStep> steps(String... names) {
        List<AgentTaskStep> steps = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            steps.add(step(i + 1, names[i], "PENDING"));
        }
        return steps;
    }

    private AgentTaskStep step(Integer index, String name, String status) {
        AgentTaskStep step = new AgentTaskStep();
        step.setId(index.longValue());
        step.setTaskRunId(100L);
        step.setStepIndex(index);
        step.setName(name);
        step.setStatus(status);
        return step;
    }
}

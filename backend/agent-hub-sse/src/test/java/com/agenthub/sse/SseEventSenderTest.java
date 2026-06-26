package com.agenthub.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseEventSenderTest {

    @Test
    void sendDoneEventDoesNotThrowException() {
        SseEventSender sender = new SseEventSender();
        SseEmitter emitter = new SseEmitter();

        assertThatCode(() -> sender.send(emitter, SseEventType.DONE, new SseEventData.Done()))
                .doesNotThrowAnyException();
    }

    @Test
    void eventTypeNamesMatchSseProtocol() {
        assertThat(Map.of(
                        SseEventType.CONVERSATION, SseEventType.CONVERSATION.eventName(),
                        SseEventType.META, SseEventType.META.eventName(),
                        SseEventType.MESSAGE, SseEventType.MESSAGE.eventName(),
                        SseEventType.REASONING_DELTA, SseEventType.REASONING_DELTA.eventName(),
                        SseEventType.ANSWER_DELTA, SseEventType.ANSWER_DELTA.eventName(),
                        SseEventType.MESSAGE_COMPLETED, SseEventType.MESSAGE_COMPLETED.eventName(),
                        SseEventType.ERROR, SseEventType.ERROR.eventName(),
                        SseEventType.DONE, SseEventType.DONE.eventName()))
                .containsEntry(SseEventType.CONVERSATION, "conversation")
                .containsEntry(SseEventType.META, "meta")
                .containsEntry(SseEventType.MESSAGE, "message")
                .containsEntry(SseEventType.REASONING_DELTA, "reasoning_delta")
                .containsEntry(SseEventType.ANSWER_DELTA, "answer_delta")
                .containsEntry(SseEventType.MESSAGE_COMPLETED, "message_completed")
                .containsEntry(SseEventType.ERROR, "error")
                .containsEntry(SseEventType.DONE, "done");
    }

    @Test
    void eventTypeNamesIncludeChatProtocolMeta() {
        assertThat(SseEventType.META.eventName()).isEqualTo("meta");
    }

    @Test
    void eventTypeNamesIncludeTaskProgressProtocol() {
        assertThat(SseEventType.TASK_PROGRESS_STARTED.eventName()).isEqualTo("task_progress_started");
        assertThat(SseEventType.TASK_PROGRESS_UPDATED.eventName()).isEqualTo("task_progress_updated");
        assertThat(SseEventType.TASK_STEP_STARTED.eventName()).isEqualTo("task_step_started");
        assertThat(SseEventType.TASK_STEP_COMPLETED.eventName()).isEqualTo("task_step_completed");
        assertThat(SseEventType.TASK_STEP_FAILED.eventName()).isEqualTo("task_step_failed");
        assertThat(SseEventType.TASK_PLAN_REVISED.eventName()).isEqualTo("task_plan_revised");
        assertThat(SseEventType.TASK_PROGRESS_COMPLETED.eventName()).isEqualTo("task_progress_completed");
    }

    @Test
    void taskProgressDataCarriesTaskProgressFieldsAndNormalizesSteps() {
        SseEventData.TaskStep step = new SseEventData.TaskStep(
                1,
                "分析需求",
                "RUNNING",
                null
        );
        SseEventData.TaskProgress progress = new SseEventData.TaskProgress(
                123L,
                "PLAN_EXECUTE",
                "RUNNING",
                "生成报告",
                "根据附件生成报告",
                1,
                2,
                3,
                null,
                List.of(step)
        );

        assertThat(progress.taskRunId()).isEqualTo(123L);
        assertThat(progress.mode()).isEqualTo("PLAN_EXECUTE");
        assertThat(progress.status()).isEqualTo("RUNNING");
        assertThat(progress.title()).isEqualTo("生成报告");
        assertThat(progress.planSummary()).isEqualTo("根据附件生成报告");
        assertThat(progress.currentStepIndex()).isEqualTo(1);
        assertThat(progress.totalSteps()).isEqualTo(2);
        assertThat(progress.revision()).isEqualTo(3);
        assertThat(progress.errorMessage()).isNull();
        assertThat(progress.steps()).containsExactly(step);
        assertThat(step.stepIndex()).isEqualTo(1);
        assertThat(step.name()).isEqualTo("分析需求");
        assertThat(step.status()).isEqualTo("RUNNING");
        assertThat(step.errorMessage()).isNull();

        SseEventData.TaskProgress emptyStepsProgress = new SseEventData.TaskProgress(
                124L,
                "PLAN_EXECUTE",
                "PLANNING",
                "生成总结",
                null,
                0,
                0,
                1,
                null,
                null
        );

        assertThat(emptyStepsProgress.steps()).isEmpty();
    }

    @Test
    void metaAndErrorDataCarryChatProtocolFields() {
        SseEventData.Meta meta = new SseEventData.Meta(
                "chat.sse.v1",
                100L,
                200L,
                "chatmsg_200_20260622_000001",
                false
        );
        assertThat(meta.protocol()).isEqualTo("chat.sse.v1");
        assertThat(meta.conversationId()).isEqualTo(100L);
        assertThat(meta.messageId()).isEqualTo(200L);
        assertThat(meta.streamId()).startsWith("chatmsg_200_");
        assertThat(meta.resumable()).isFalse();

        SseEventData.Error error = SseEventData.Error.failed(
                "LLM_STREAM_FAILED",
                "模型调用失败",
                200L,
                "LLM",
                false
        );
        assertThat(error.code()).isEqualTo("LLM_STREAM_FAILED");
        assertThat(error.message()).isEqualTo("模型调用失败");
        assertThat(error.messageId()).isEqualTo(200L);
        assertThat(error.fatal()).isTrue();
        assertThat(error.recoverable()).isFalse();
        assertThat(error.retryable()).isFalse();
        assertThat(error.source()).isEqualTo("LLM");
        assertThat(error.status()).isEqualTo("FAILED");
        assertThat(error.traceId()).isNull();
        assertThat(error.details()).isEmpty();
    }

    @Test
    void errorDataKeepsLegacyConstructorDefaults() {
        SseEventData.Error error = new SseEventData.Error("UNKNOWN", "服务异常");

        assertThat(error.code()).isEqualTo("UNKNOWN");
        assertThat(error.message()).isEqualTo("服务异常");
        assertThat(error.messageId()).isNull();
        assertThat(error.fatal()).isTrue();
        assertThat(error.recoverable()).isFalse();
        assertThat(error.retryable()).isFalse();
        assertThat(error.source()).isEqualTo("SERVER");
        assertThat(error.status()).isEqualTo("FAILED");
        assertThat(error.traceId()).isNull();
        assertThat(error.details()).isEmpty();
    }

    @Test
    void errorDataCarriesRetryTraceAndDetailsFields() {
        SseEventData.Error error = SseEventData.Error.failed(
                "TOOL_LOOP_FAILED",
                "工具调用失败",
                200L,
                "TOOL",
                true,
                "trace-1",
                Map.of("toolCallId", "call_1")
        );

        assertThat(error.code()).isEqualTo("TOOL_LOOP_FAILED");
        assertThat(error.retryable()).isTrue();
        assertThat(error.recoverable()).isTrue();
        assertThat(error.traceId()).isEqualTo("trace-1");
        assertThat(error.details()).containsEntry("toolCallId", "call_1");
    }
}

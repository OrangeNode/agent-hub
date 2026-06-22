package com.agenthub.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        assertThat(error.source()).isEqualTo("LLM");
        assertThat(error.status()).isEqualTo("FAILED");
    }

    @Test
    void errorDataKeepsLegacyConstructorDefaults() {
        SseEventData.Error error = new SseEventData.Error("UNKNOWN", "服务异常");

        assertThat(error.code()).isEqualTo("UNKNOWN");
        assertThat(error.message()).isEqualTo("服务异常");
        assertThat(error.messageId()).isNull();
        assertThat(error.fatal()).isTrue();
        assertThat(error.recoverable()).isFalse();
        assertThat(error.source()).isEqualTo("SERVER");
        assertThat(error.status()).isEqualTo("FAILED");
    }
}

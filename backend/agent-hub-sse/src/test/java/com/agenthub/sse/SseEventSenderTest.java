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
                        SseEventType.MESSAGE, SseEventType.MESSAGE.eventName(),
                        SseEventType.REASONING_DELTA, SseEventType.REASONING_DELTA.eventName(),
                        SseEventType.ANSWER_DELTA, SseEventType.ANSWER_DELTA.eventName(),
                        SseEventType.MESSAGE_COMPLETED, SseEventType.MESSAGE_COMPLETED.eventName(),
                        SseEventType.ERROR, SseEventType.ERROR.eventName(),
                        SseEventType.DONE, SseEventType.DONE.eventName()))
                .containsEntry(SseEventType.CONVERSATION, "conversation")
                .containsEntry(SseEventType.MESSAGE, "message")
                .containsEntry(SseEventType.REASONING_DELTA, "reasoning_delta")
                .containsEntry(SseEventType.ANSWER_DELTA, "answer_delta")
                .containsEntry(SseEventType.MESSAGE_COMPLETED, "message_completed")
                .containsEntry(SseEventType.ERROR, "error")
                .containsEntry(SseEventType.DONE, "done");
    }
}

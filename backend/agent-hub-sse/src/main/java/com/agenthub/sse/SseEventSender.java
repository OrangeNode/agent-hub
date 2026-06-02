package com.agenthub.sse;

import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseEventSender {

    public void send(SseEmitter emitter, SseEventType eventType, SseEventData data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventType.eventName())
                .data(data));
    }

    public void complete(SseEmitter emitter) {
        emitter.complete();
    }

    public void completeWithError(SseEmitter emitter, Throwable throwable) {
        emitter.completeWithError(throwable);
    }
}

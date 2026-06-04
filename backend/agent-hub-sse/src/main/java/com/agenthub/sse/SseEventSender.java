package com.agenthub.sse;

import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseEventSender {

    /**
     * 按统一事件名发送 SSE 数据。
     */
    public void send(SseEmitter emitter, SseEventType eventType, SseEventData data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventType.eventName())
                .data(data));
    }

    /**
     * 正常完成 SSE 连接。
     */
    public void complete(SseEmitter emitter) {
        emitter.complete();
    }

    /**
     * 带异常信息关闭 SSE 连接。
     */
    public void completeWithError(SseEmitter emitter, Throwable throwable) {
        emitter.completeWithError(throwable);
    }
}

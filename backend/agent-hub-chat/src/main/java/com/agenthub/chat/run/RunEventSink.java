package com.agenthub.chat.run;

import com.agenthub.sse.SseEventData;
import com.agenthub.sse.SseEventSender;
import com.agenthub.sse.SseEventType;
import java.io.IOException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 运行事件的输出通道。发送失败视为客户端断开，由调用方决定是否终止运行。
 */
public class RunEventSink {

    private final SseEmitter emitter;
    private final SseEventSender sender;

    public RunEventSink(SseEmitter emitter, SseEventSender sender) {
        this.emitter = emitter;
        this.sender = sender;
    }

    public boolean send(SseEventType eventType, SseEventData data) {
        try {
            sender.send(emitter, eventType, data);
            return true;
        } catch (IOException | IllegalStateException exception) {
            try {
                sender.completeWithError(emitter, exception);
            } catch (IllegalStateException ignored) {
                // emitter 已经进入错误终态时无需重复完成。
            }
            return false;
        }
    }

    public void complete() {
        sender.complete(emitter);
    }
}

package com.agenthub.chat.message.support;

import com.agenthub.chat.message.entity.ChatMessage;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次助手回复在内存中的累积状态。
 * finished 保证完成、失败、中止三种终态只落库一次。
 */
public class AssistantReply {

    private final ChatMessage message;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoningContent = new StringBuilder();
    private final ProcessEventRecorder processRecorder;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    public AssistantReply(ChatMessage message) {
        this(message, new ProcessEventRecorder());
    }

    public AssistantReply(ChatMessage message, ProcessEventRecorder processRecorder) {
        this.message = message;
        this.processRecorder = processRecorder;
    }

    public ChatMessage message() {
        return message;
    }

    public Long messageId() {
        return message.getId();
    }

    public ProcessEventRecorder processRecorder() {
        return processRecorder;
    }

    public void appendContent(String delta) {
        content.append(delta);
        message.setContent(content.toString());
    }

    public void appendReasoning(String delta) {
        reasoningContent.append(delta);
        processRecorder.appendReasoning(delta);
        message.setReasoningContent(reasoningContent.toString());
    }

    public String content() {
        return content.toString();
    }

    public String reasoningContent() {
        return reasoningContent.toString();
    }

    public boolean hasOutput() {
        return !content.isEmpty() || !reasoningContent.isEmpty();
    }

    public boolean isFinished() {
        return finished.get();
    }

    /**
     * 抢占终态写入权，只有第一个调用方返回 true。
     */
    public boolean tryFinish() {
        return finished.compareAndSet(false, true);
    }
}

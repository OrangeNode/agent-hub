package com.agenthub.tool.runtime;

public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        ToolEventSink eventSink
) {

    public ToolExecutionContext {
        if (eventSink == null) {
            eventSink = ToolEventSink.noop();
        }
    }
}

package com.agenthub.tool.runtime;

import java.util.List;

public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        List<Long> skillIds,
        ToolEventSink eventSink
) {

    public ToolExecutionContext(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            String agentKey,
            ToolEventSink eventSink
    ) {
        this(userId, conversationId, assistantMessageId, agentKey, List.of(), eventSink);
    }

    public ToolExecutionContext {
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        if (eventSink == null) {
            eventSink = ToolEventSink.noop();
        }
    }
}

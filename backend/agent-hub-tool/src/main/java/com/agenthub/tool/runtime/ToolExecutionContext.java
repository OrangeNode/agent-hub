package com.agenthub.tool.runtime;

import java.util.List;

public record ToolExecutionContext(
        Long userId,
        Long conversationId,
        Long assistantMessageId,
        String agentKey,
        Long agentId,
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
        this(userId, conversationId, assistantMessageId, agentKey, null, List.of(), eventSink);
    }

    public ToolExecutionContext(
            Long userId,
            Long conversationId,
            Long assistantMessageId,
            String agentKey,
            List<Long> skillIds,
            ToolEventSink eventSink
    ) {
        this(userId, conversationId, assistantMessageId, agentKey, null, skillIds, eventSink);
    }

    public ToolExecutionContext {
        skillIds = skillIds == null ? List.of() : List.copyOf(skillIds);
        if (eventSink == null) {
            eventSink = ToolEventSink.noop();
        }
    }
}

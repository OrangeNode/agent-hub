package com.agenthub.chat.task.runtime;

public record AgentRunDecision(
        RunMode runMode,
        ToolPolicy toolPolicy,
        PlanPolicy planPolicy
) {
}

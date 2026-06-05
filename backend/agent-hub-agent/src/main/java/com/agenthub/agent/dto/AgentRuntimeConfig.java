package com.agenthub.agent.dto;

public record AgentRuntimeConfig(
        Long agentId,
        String agentKey,
        String name,
        String systemPrompt,
        Long defaultModelId
) {
}

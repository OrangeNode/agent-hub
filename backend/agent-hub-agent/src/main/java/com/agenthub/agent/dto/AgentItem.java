package com.agenthub.agent.dto;

public record AgentItem(
        Long agentId,
        String agentKey,
        String name,
        String shortName,
        String description,
        String systemPrompt,
        Long defaultModelId,
        String defaultModelName,
        boolean enabled,
        int sort
) {
}

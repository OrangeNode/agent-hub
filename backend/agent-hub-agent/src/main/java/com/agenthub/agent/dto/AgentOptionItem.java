package com.agenthub.agent.dto;

public record AgentOptionItem(
        Long agentId,
        String agentKey,
        String name,
        String shortName,
        String description,
        Long defaultModelId,
        boolean enabled,
        int sort
) {
}

package com.agenthub.agent.command;

public record CreateAgentCommand(
        String agentKey,
        String name,
        String shortName,
        String description,
        String systemPrompt,
        Long defaultModelId,
        Boolean enabled,
        Integer sort
) {
}

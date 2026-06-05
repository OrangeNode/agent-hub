package com.agenthub.agent.command;

public record UpdateAgentCommand(
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

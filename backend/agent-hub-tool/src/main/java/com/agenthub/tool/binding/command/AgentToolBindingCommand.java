package com.agenthub.tool.binding.command;

public record AgentToolBindingCommand(
        String toolName,
        String providerType,
        Long providerRefId,
        Integer sort
) {
}

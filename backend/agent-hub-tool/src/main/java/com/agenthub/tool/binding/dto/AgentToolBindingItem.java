package com.agenthub.tool.binding.dto;

public record AgentToolBindingItem(
        Long bindingId,
        Long agentId,
        String toolName,
        String providerType,
        Long providerRefId,
        String serverKey,
        String serverName,
        String toolDisplayName,
        String toolDescription,
        Boolean serverEnabled,
        Boolean toolEnabled,
        Boolean enabled,
        Integer sort
) {
}

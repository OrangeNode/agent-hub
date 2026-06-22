package com.agenthub.tool.binding.dto;

public record AgentToolOptionItem(
        String toolName,
        String providerType,
        Long providerRefId,
        String serverKey,
        String serverName,
        String remoteToolName,
        String exposedToolName,
        String displayName,
        String description,
        Boolean serverEnabled,
        Boolean toolEnabled
) {
}

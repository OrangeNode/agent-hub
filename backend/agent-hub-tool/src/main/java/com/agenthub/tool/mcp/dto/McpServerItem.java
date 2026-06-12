package com.agenthub.tool.mcp.dto;

import java.time.OffsetDateTime;

public record McpServerItem(
        Long serverId,
        String serverKey,
        String name,
        String description,
        String endpointUrl,
        String transportType,
        String protocolVersion,
        String authType,
        boolean hasAuthToken,
        String extraHeaders,
        Integer connectTimeoutSeconds,
        Integer requestTimeoutSeconds,
        Boolean enabled,
        String lastSyncStatus,
        String lastSyncMessage,
        OffsetDateTime lastSyncedAt,
        Integer toolCount,
        Integer sort
) {
}

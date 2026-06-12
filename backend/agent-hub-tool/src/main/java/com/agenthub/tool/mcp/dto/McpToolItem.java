package com.agenthub.tool.mcp.dto;

import java.time.OffsetDateTime;

public record McpToolItem(
        Long toolId,
        Long serverId,
        String serverKey,
        String toolName,
        String exposedToolName,
        String displayName,
        String description,
        String inputSchema,
        Boolean enabled,
        OffsetDateTime lastSeenAt
) {
}

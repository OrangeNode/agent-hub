package com.agenthub.tool.mcp.dto;

import java.time.OffsetDateTime;

public record McpToolCallLogItem(
        Long callId,
        String serverKey,
        String exposedToolName,
        String remoteToolName,
        Long conversationId,
        Long userId,
        String status,
        String errorMessage,
        Long durationMs,
        OffsetDateTime startedAt
) {
}

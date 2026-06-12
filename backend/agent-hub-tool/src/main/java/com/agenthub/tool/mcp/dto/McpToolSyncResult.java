package com.agenthub.tool.mcp.dto;

public record McpToolSyncResult(
        boolean success,
        int syncedCount,
        String message
) {
}

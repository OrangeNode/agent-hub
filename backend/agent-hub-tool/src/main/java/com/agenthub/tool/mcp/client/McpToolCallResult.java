package com.agenthub.tool.mcp.client;

public record McpToolCallResult(
        boolean error,
        String content
) {
}

package com.agenthub.tool.mcp.dto;

public record McpConnectionTestResult(
        boolean success,
        String protocolVersion,
        String serverName,
        String message
) {
}

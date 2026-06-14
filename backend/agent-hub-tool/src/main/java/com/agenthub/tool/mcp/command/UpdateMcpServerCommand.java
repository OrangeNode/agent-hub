package com.agenthub.tool.mcp.command;

public record UpdateMcpServerCommand(
        String name,
        String description,
        String endpointUrl,
        String transportType,
        String protocolVersion,
        String authType,
        String authToken,
        String extraHeaders,
        Integer connectTimeoutSeconds,
        Integer requestTimeoutSeconds,
        Boolean enabled,
        Integer sort
) {
}

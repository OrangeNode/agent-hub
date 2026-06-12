package com.agenthub.tool.runtime;

import java.util.Map;

public record ToolExecutionRequest(
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
) {

    public ToolExecutionRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

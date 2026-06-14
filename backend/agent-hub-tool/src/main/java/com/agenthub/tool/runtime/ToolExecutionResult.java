package com.agenthub.tool.runtime;

public record ToolExecutionResult(String status, String content) {

    public ToolExecutionResult {
        if (content == null) {
            content = "";
        }
    }

    public static ToolExecutionResult completed(String content) {
        return new ToolExecutionResult("completed", content);
    }

    public static ToolExecutionResult failed(String content) {
        return new ToolExecutionResult("failed", content);
    }
}

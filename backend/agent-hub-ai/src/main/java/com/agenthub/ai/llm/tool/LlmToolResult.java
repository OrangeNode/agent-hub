package com.agenthub.ai.llm.tool;

public record LlmToolResult(
        String toolCallId,
        String name,
        String content
) {
}

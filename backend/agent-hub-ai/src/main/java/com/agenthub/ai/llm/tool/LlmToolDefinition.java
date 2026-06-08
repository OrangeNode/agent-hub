package com.agenthub.ai.llm.tool;

import java.util.Map;

public record LlmToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {
}

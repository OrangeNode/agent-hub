package com.agenthub.ai.llm;

import java.util.List;

public record LlmStreamRequest(
        String baseUrl,
        String apiKey,
        String apiVersion,
        String model,
        List<LlmMessage> messages,
        Integer maxOutputTokens,
        boolean reasoningRequested
) {
}

package com.agenthub.ai.llm;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;

public record LlmStreamRequest(
        String baseUrl,
        String apiKey,
        String apiVersion,
        String model,
        List<LlmMessage> messages,
        Integer maxOutputTokens,
        boolean reasoningRequested,
        List<LlmToolDefinition> tools
) {

    public LlmStreamRequest(
            String baseUrl,
            String apiKey,
            String apiVersion,
            String model,
            List<LlmMessage> messages,
            Integer maxOutputTokens,
            boolean reasoningRequested
    ) {
        this(baseUrl, apiKey, apiVersion, model, messages, maxOutputTokens, reasoningRequested, List.of());
    }
}

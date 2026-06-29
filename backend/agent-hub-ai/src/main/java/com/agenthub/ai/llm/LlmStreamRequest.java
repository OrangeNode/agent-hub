package com.agenthub.ai.llm;

import com.agenthub.ai.llm.tool.LlmToolDefinition;
import java.util.List;

public record LlmStreamRequest(
        String baseUrl,
        String apiKey,
        String authType,
        String apiVersion,
        String extraHeaders,
        String extraBody,
        String model,
        List<LlmMessage> messages,
        Integer maxOutputTokens,
        boolean reasoningRequested,
        List<LlmToolDefinition> tools
) {

    private static final String DEFAULT_AUTH_TYPE = "BEARER";
    private static final String DEFAULT_JSON = "{}";

    public LlmStreamRequest {
        authType = hasText(authType) ? authType : DEFAULT_AUTH_TYPE;
        extraHeaders = hasText(extraHeaders) ? extraHeaders : DEFAULT_JSON;
        extraBody = hasText(extraBody) ? extraBody : DEFAULT_JSON;
        tools = tools == null ? List.of() : tools;
    }

    public LlmStreamRequest(
            String baseUrl,
            String apiKey,
            String authType,
            String apiVersion,
            String extraHeaders,
            String extraBody,
            String model,
            List<LlmMessage> messages,
            Integer maxOutputTokens,
            boolean reasoningRequested
    ) {
        this(baseUrl, apiKey, authType, apiVersion, extraHeaders, extraBody,
                model, messages, maxOutputTokens, reasoningRequested, List.of());
    }

    public LlmStreamRequest(
            String baseUrl,
            String apiKey,
            String apiVersion,
            String model,
            List<LlmMessage> messages,
            Integer maxOutputTokens,
            boolean reasoningRequested,
            List<LlmToolDefinition> tools
    ) {
        this(baseUrl, apiKey, DEFAULT_AUTH_TYPE, apiVersion, DEFAULT_JSON, DEFAULT_JSON,
                model, messages, maxOutputTokens, reasoningRequested, tools);
    }

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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

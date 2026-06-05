package com.agenthub.ai.provider.dto;

public record AiProviderConnectionTestResult(
        boolean success,
        String message,
        Integer statusCode
) {
}

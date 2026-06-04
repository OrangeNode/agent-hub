package com.agenthub.ai.model.dto;

public record AiModelRuntimeConfig(
        Long modelId,
        Long providerId,
        String providerCode,
        String providerName,
        String providerType,
        String protocolType,
        String baseUrl,
        String apiKeyCipher,
        String authType,
        String apiVersion,
        String extraHeaders,
        String extraBody,
        String modelCode,
        String displayName,
        boolean streamSupported,
        boolean reasoningSupported,
        boolean imageInputSupported,
        Integer imageTokenEstimate,
        Integer contextWindow,
        Integer maxOutputTokens,
        boolean enabled,
        boolean providerEnabled
) {
}

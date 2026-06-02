package com.agenthub.ai.model.dto;

public record AiModelItem(
        Long modelId,
        Long providerId,
        String providerCode,
        String providerName,
        String modelCode,
        String displayName,
        String capabilityType,
        boolean streamSupported,
        boolean reasoningSupported,
        Integer contextWindow,
        Integer maxOutputTokens,
        boolean defaultModel,
        boolean enabled,
        int sort
) {
}

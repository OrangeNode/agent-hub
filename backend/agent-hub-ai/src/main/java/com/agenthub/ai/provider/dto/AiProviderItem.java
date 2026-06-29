package com.agenthub.ai.provider.dto;

public record AiProviderItem(
        Long providerId,
        String code,
        String name,
        String providerType,
        String protocolType,
        String baseUrl,
        String authType,
        String apiVersion,
        String extraHeaders,
        String extraBody,
        boolean hasApiKey,
        boolean enabled,
        int sort
) {
}

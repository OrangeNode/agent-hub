package com.agenthub.ai.provider.command;

public record CreateProviderCommand(
        String code,
        String name,
        String providerType,
        String protocolType,
        String baseUrl,
        String apiKey,
        String authType,
        String apiVersion,
        String extraHeaders,
        String extraBody,
        Boolean enabled,
        Integer sort
) {
}

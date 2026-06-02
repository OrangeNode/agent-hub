package com.agenthub.ai.provider.command;

public record UpdateProviderCommand(
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

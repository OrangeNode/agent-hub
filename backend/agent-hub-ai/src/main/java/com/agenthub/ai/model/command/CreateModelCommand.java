package com.agenthub.ai.model.command;

public record CreateModelCommand(
        Long providerId,
        String modelCode,
        String displayName,
        String capabilityType,
        Boolean streamSupported,
        Boolean reasoningSupported,
        Integer contextWindow,
        Integer maxOutputTokens,
        Boolean defaultModel,
        Boolean enabled,
        Integer sort,
        boolean imageInputSupported,
        Integer imageTokenEstimate
) {
}

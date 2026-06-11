package com.agenthub.sandbox.admin.dto;

public record SandboxRisk(
        String level,
        String code,
        String title,
        String message,
        String targetType,
        String targetId,
        String recommendedAction
) {
}

package com.agenthub.chat.task.dto;

public record TaskStepItem(
        Integer stepIndex,
        String name,
        String status,
        String errorMessage
) {
}

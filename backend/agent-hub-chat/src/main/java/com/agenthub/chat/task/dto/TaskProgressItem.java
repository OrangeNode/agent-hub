package com.agenthub.chat.task.dto;

import java.util.List;

public record TaskProgressItem(
        Long taskRunId,
        String mode,
        String status,
        String title,
        String planSummary,
        Integer currentStepIndex,
        Integer totalSteps,
        Integer revision,
        String errorMessage,
        List<TaskStepItem> steps
) {
}

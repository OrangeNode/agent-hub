package com.agenthub.chat.message.dto;

import java.util.List;

public record MessageProcessEvent(
        String type,
        String content,
        ToolProcess tool,
        TaskProcess task
) {

    public MessageProcessEvent(String type, String content, ToolProcess tool) {
        this(type, content, tool, null);
    }

    public record ToolProcess(
            String toolCallId,
            String name,
            String status,
            String stdout,
            String stderr,
            String result
    ) {
    }

    public record TaskProcess(
            Long taskRunId,
            String mode,
            String status,
            String title,
            String planSummary,
            Integer currentStepIndex,
            Integer totalSteps,
            Integer revision,
            String errorMessage,
            List<TaskStepProcess> steps
    ) {
        public TaskProcess {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    public record TaskStepProcess(
            Integer stepIndex,
            String name,
            String status,
            String errorMessage
    ) {
    }
}

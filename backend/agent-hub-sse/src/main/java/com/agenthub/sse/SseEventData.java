package com.agenthub.sse;

import java.util.List;
import java.util.Map;

public sealed interface SseEventData permits SseEventData.Conversation,
        SseEventData.Meta,
        SseEventData.Message,
        SseEventData.Delta,
        SseEventData.ToolCall,
        SseEventData.ToolOutput,
        SseEventData.ToolResult,
        SseEventData.MessageCompleted,
        SseEventData.TaskProgress,
        SseEventData.Error,
        SseEventData.Done {

    record Conversation(Long conversationId, String title) implements SseEventData {
    }

    record Meta(String protocol, Long conversationId, Long messageId, String streamId, boolean resumable)
            implements SseEventData {
    }

    record Message(Long messageId, String role) implements SseEventData {
    }

    record Delta(String content) implements SseEventData {
    }

    record ToolCall(String toolCallId, String name) implements SseEventData {
    }

    record ToolOutput(String toolCallId, String stream, String content) implements SseEventData {
    }

    record ToolResult(String toolCallId, String name, String status, String content) implements SseEventData {
    }

    record MessageCompleted(Long messageId, String status) implements SseEventData {
    }

    record TaskProgress(Long taskRunId,
                        String mode,
                        String status,
                        String title,
                        String planSummary,
                        Integer currentStepIndex,
                        Integer totalSteps,
                        Integer revision,
                        String errorMessage,
                        List<TaskStep> steps) implements SseEventData {

        public TaskProgress {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record TaskStep(Integer stepIndex, String name, String status, String errorMessage) {
    }

    record Error(String code,
                 String message,
                 Long messageId,
                 boolean fatal,
                 boolean recoverable,
                 String source,
                 String status,
                 boolean retryable,
                 String traceId,
                 Map<String, Object> details) implements SseEventData {

        public Error {
            details = details == null ? Map.of() : Map.copyOf(details);
        }

        public Error(String code, String message) {
            this(code, message, null, true, false, "SERVER", "FAILED", false, null, Map.of());
        }

        public static Error failed(String code, String message, Long messageId, String source, boolean recoverable) {
            return failed(code, message, messageId, source, recoverable, null, Map.of());
        }

        public static Error failed(String code,
                                   String message,
                                   Long messageId,
                                   String source,
                                   boolean retryable,
                                   String traceId,
                                   Map<String, ?> details) {
            Map<String, Object> resolvedDetails = details == null
                    ? Map.of()
                    : Map.copyOf(details);
            return new Error(code, message, messageId, true, retryable, source, "FAILED",
                    retryable, traceId, resolvedDetails);
        }
    }

    record Done() implements SseEventData {
    }
}

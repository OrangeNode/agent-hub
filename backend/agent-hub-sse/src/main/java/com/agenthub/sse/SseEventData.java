package com.agenthub.sse;

public sealed interface SseEventData permits SseEventData.Conversation,
        SseEventData.Meta,
        SseEventData.Message,
        SseEventData.Delta,
        SseEventData.ToolCall,
        SseEventData.ToolOutput,
        SseEventData.ToolResult,
        SseEventData.MessageCompleted,
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

    record Error(String code,
                 String message,
                 Long messageId,
                 boolean fatal,
                 boolean recoverable,
                 String source,
                 String status) implements SseEventData {

        public Error(String code, String message) {
            this(code, message, null, true, false, "SERVER", "FAILED");
        }

        public static Error failed(String code, String message, Long messageId, String source, boolean recoverable) {
            return new Error(code, message, messageId, true, recoverable, source, "FAILED");
        }
    }

    record Done() implements SseEventData {
    }
}

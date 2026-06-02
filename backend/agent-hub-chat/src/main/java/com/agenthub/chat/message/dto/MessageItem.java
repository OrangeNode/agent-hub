package com.agenthub.chat.message.dto;

public record MessageItem(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        String reasoningContent,
        String status
) {
}

package com.agenthub.chat.conversation.dto;

public record ConversationItem(
        Long conversationId,
        String title,
        String agentKey,
        Long modelId,
        String status
) {
}

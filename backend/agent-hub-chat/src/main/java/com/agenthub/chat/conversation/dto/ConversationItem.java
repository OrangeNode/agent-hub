package com.agenthub.chat.conversation.dto;

import java.time.OffsetDateTime;

public record ConversationItem(
        Long conversationId,
        String title,
        String agentKey,
        Long modelId,
        String status,
        OffsetDateTime updatedAt
) {
}

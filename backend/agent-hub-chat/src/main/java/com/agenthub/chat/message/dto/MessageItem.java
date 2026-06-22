package com.agenthub.chat.message.dto;

import com.agenthub.storage.attachment.dto.AttachmentItem;
import java.util.List;

public record MessageItem(
        Long messageId,
        Long conversationId,
        String role,
        String content,
        String reasoningContent,
        String status,
        String errorMessage,
        List<AttachmentItem> attachments,
        List<MessageProcessEvent> processEvents
) {
}

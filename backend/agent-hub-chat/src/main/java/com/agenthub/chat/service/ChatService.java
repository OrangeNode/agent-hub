package com.agenthub.chat.service;

import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.message.dto.MessageItem;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    SseEmitter stream(Long userId, Long conversationId, Long modelId, String agentKey, String prompt, boolean thinkingEnabled);

    List<ConversationItem> listConversations(Long userId);

    List<MessageItem> listMessages(Long userId, Long conversationId);

    void deleteConversation(Long userId, Long conversationId);

    void renameConversation(Long userId, Long conversationId, String title);
}

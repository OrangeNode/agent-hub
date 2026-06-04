package com.agenthub.chat.service;

import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.message.dto.MessageItem;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    /**
     * 发起或继续会话，并通过 SSE 流式返回模型响应。
     */
    SseEmitter stream(Long userId, Long conversationId, Long modelId, String agentKey, String prompt, boolean thinkingEnabled);

    /**
     * 查询用户的有效会话列表。
     */
    List<ConversationItem> listConversations(Long userId);

    /**
     * 查询指定会话的消息列表，并校验会话归属。
     */
    List<MessageItem> listMessages(Long userId, Long conversationId);

    /**
     * 软删除用户自己的会话。
     */
    void deleteConversation(Long userId, Long conversationId);

    /**
     * 修改用户自己的会话标题。
     */
    void renameConversation(Long userId, Long conversationId, String title);
}

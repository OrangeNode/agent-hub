package com.agenthub.chat.controller;

import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.service.ChatService;
import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/stream")
    public SseEmitter stream(
            Authentication authentication,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) Long modelId,
            @RequestParam(required = false) String agentKey,
            @RequestParam String prompt
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return chatService.stream(loginUser.userId(), conversationId, modelId, agentKey, prompt);
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationItem>> listConversations(Authentication authentication) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(chatService.listConversations(loginUser.userId()));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<MessageItem>> listMessages(
            Authentication authentication,
            @PathVariable Long conversationId
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(chatService.listMessages(loginUser.userId(), conversationId));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(Authentication authentication, @PathVariable Long conversationId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        chatService.deleteConversation(loginUser.userId(), conversationId);
        return ApiResponse.success();
    }
}

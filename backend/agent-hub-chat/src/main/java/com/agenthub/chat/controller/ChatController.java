package com.agenthub.chat.controller;

import com.agenthub.chat.conversation.dto.ConversationItem;
import com.agenthub.chat.message.dto.MessageItem;
import com.agenthub.chat.service.ChatService;
import com.agenthub.common.api.ApiResponse;
import com.agenthub.security.principal.LoginUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 发起或继续流式聊天会话。
     */
    @PostMapping("/stream")
    public SseEmitter stream(
            Authentication authentication,
            @Valid @RequestBody ChatStreamRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return chatService.stream(
                loginUser.userId(),
                request.conversationId(),
                request.modelId(),
                request.agentKey(),
                request.prompt(),
                Boolean.TRUE.equals(request.thinkingEnabled()),
                request.attachmentIds(),
                request.reuseAttachmentIds()
        );
    }

    /**
     * 查询当前用户的会话列表。
     */
    @GetMapping("/conversations")
    public ApiResponse<List<ConversationItem>> listConversations(Authentication authentication) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(chatService.listConversations(loginUser.userId()));
    }

    /**
     * 查询当前用户指定会话的消息列表。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<MessageItem>> listMessages(
            Authentication authentication,
            @PathVariable Long conversationId
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        return ApiResponse.success(chatService.listMessages(loginUser.userId(), conversationId));
    }

    /**
     * 删除当前用户指定会话。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(Authentication authentication, @PathVariable Long conversationId) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        chatService.deleteConversation(loginUser.userId(), conversationId);
        return ApiResponse.success();
    }

    /**
     * 重命名当前用户指定会话。
     */
    @PatchMapping("/conversations/{conversationId}")
    public ApiResponse<Void> renameConversation(
            Authentication authentication,
            @PathVariable Long conversationId,
            @Valid @RequestBody RenameConversationRequest request
    ) {
        LoginUser loginUser = (LoginUser) authentication.getPrincipal();
        chatService.renameConversation(loginUser.userId(), conversationId, request.title());
        return ApiResponse.success();
    }

    public record ChatStreamRequest(
            @NotBlank(message = "消息内容不能为空")
            @Size(max = 8000, message = "消息内容不能超过 8000 个字符")
            String prompt,
            Long conversationId,
            Long modelId,
            String agentKey,
            Boolean thinkingEnabled,
            List<Long> attachmentIds,
            List<Long> reuseAttachmentIds
    ) {
    }

    public record RenameConversationRequest(
            @NotBlank(message = "会话标题不能为空")
            @Size(max = 50, message = "会话标题不能超过 50 个字符")
            String title
    ) {
    }
}

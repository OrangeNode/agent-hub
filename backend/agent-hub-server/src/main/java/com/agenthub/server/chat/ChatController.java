package com.agenthub.server.chat;

import com.agenthub.chat.ChatStreamService;
import com.agenthub.chat.conversation.ConversationItem;
import com.agenthub.common.api.ApiResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatStreamService chatStreamService;

    public ChatController(ChatStreamService chatStreamService) {
        this.chatStreamService = chatStreamService;
    }

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationItem>> listConversations() {
        return ApiResponse.success(List.of(
                new ConversationItem("conv-1", "初始化欢迎会话", "2026-05-29 12:00"),
                new ConversationItem("conv-2", "产品问答草稿", "2026-05-29 10:20")
        ));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "你好") String prompt) {
        SseEmitter emitter = new SseEmitter(0L);

        chatStreamService.stream(prompt).subscribe(
                chunk -> sendChunk(emitter, chunk.content()),
                emitter::completeWithError,
                emitter::complete
        );

        return emitter;
    }

    private void sendChunk(SseEmitter emitter, String content) {
        try {
            emitter.send(content);
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        }
    }
}

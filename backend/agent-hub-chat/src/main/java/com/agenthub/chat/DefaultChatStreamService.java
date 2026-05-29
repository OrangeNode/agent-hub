package com.agenthub.chat;

import com.agenthub.ai.llm.ChatChunk;
import com.agenthub.ai.llm.ChatMessage;
import com.agenthub.ai.llm.ChatRequest;
import com.agenthub.ai.llm.LlmClient;
import java.util.List;
import reactor.core.publisher.Flux;

public class DefaultChatStreamService implements ChatStreamService {

    private final LlmClient llmClient;

    public DefaultChatStreamService(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public Flux<ChatChunk> stream(String prompt) {
        return llmClient.stream(new ChatRequest("mock", List.of(new ChatMessage("user", prompt))));
    }
}

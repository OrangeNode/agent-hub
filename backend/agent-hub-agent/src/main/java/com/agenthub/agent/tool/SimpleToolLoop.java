package com.agenthub.agent.tool;

import com.agenthub.ai.llm.ChatChunk;
import com.agenthub.ai.llm.ChatRequest;
import com.agenthub.ai.llm.LlmClient;
import com.agenthub.agent.runtime.RuntimeEvent;
import reactor.core.publisher.Flux;

public class SimpleToolLoop implements ToolLoop {

    private final LlmClient llmClient;

    public SimpleToolLoop(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public Flux<RuntimeEvent> execute(ChatRequest request) {
        return llmClient.stream(request).map(this::toModelEvent);
    }

    private RuntimeEvent toModelEvent(ChatChunk chunk) {
        return new RuntimeEvent("model", chunk.content());
    }
}

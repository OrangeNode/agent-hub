package com.agenthub.ai.llm;

import reactor.core.publisher.Flux;

public interface LlmClient {

    ChatMessage complete(ChatRequest request);

    Flux<ChatChunk> stream(ChatRequest request);
}

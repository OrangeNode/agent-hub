package com.agenthub.chat;

import com.agenthub.ai.llm.ChatChunk;
import reactor.core.publisher.Flux;

public interface ChatStreamService {

    Flux<ChatChunk> stream(String prompt);
}

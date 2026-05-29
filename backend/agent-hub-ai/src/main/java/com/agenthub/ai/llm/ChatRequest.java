package com.agenthub.ai.llm;

import java.util.List;

public record ChatRequest(String model, List<ChatMessage> messages) {
}

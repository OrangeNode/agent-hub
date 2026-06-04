package com.agenthub.ai.llm;

import java.util.List;

public record LlmMessage(String role, String content, List<LlmContentPart> parts) {

    public LlmMessage(String role, String content) {
        this(role, content, null);
    }

    public static LlmMessage user(List<LlmContentPart> parts) {
        return new LlmMessage("user", null, parts);
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }
}

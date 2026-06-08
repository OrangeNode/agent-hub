package com.agenthub.ai.llm;

import java.util.List;

public record LlmMessage(String role, String content, List<LlmContentPart> parts, String toolCallId, String toolName) {

    public LlmMessage(String role, String content) {
        this(role, content, null, null, null);
    }

    public LlmMessage(String role, String content, List<LlmContentPart> parts) {
        this(role, content, parts, null, null);
    }

    public static LlmMessage user(List<LlmContentPart> parts) {
        return new LlmMessage("user", null, parts);
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return new LlmMessage("tool", content, null, toolCallId, toolName);
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }
}

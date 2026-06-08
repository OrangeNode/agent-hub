package com.agenthub.ai.llm;

import com.agenthub.ai.llm.tool.LlmToolCall;
import java.util.List;

public record LlmMessage(
        String role,
        String content,
        List<LlmContentPart> parts,
        String toolCallId,
        String toolName,
        List<LlmToolCall> toolCalls
) {

    public LlmMessage(String role, String content) {
        this(role, content, null, null, null, null);
    }

    public LlmMessage(String role, String content, List<LlmContentPart> parts) {
        this(role, content, parts, null, null, null);
    }

    public static LlmMessage user(List<LlmContentPart> parts) {
        return new LlmMessage("user", null, parts);
    }

    public static LlmMessage tool(String toolCallId, String toolName, String content) {
        return new LlmMessage("tool", content, null, toolCallId, toolName, null);
    }

    public static LlmMessage assistantToolCalls(List<LlmToolCall> toolCalls) {
        return new LlmMessage("assistant", null, null, null, null, toolCalls);
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}

package com.agenthub.ai.llm;

public record LlmContentPart(String type, String text, String imageUrl) {

    public static LlmContentPart text(String text) {
        return new LlmContentPart("text", text, null);
    }

    public static LlmContentPart imageUrl(String imageUrl) {
        return new LlmContentPart("image_url", null, imageUrl);
    }
}

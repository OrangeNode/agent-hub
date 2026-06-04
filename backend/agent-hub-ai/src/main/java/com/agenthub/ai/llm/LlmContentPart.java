package com.agenthub.ai.llm;

public record LlmContentPart(String type, String text, String imageUrl) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_IMAGE_URL = "image_url";

    public LlmContentPart {
        if (type == null) {
            throw new IllegalArgumentException("不支持的内容片段类型");
        }
        switch (type) {
            case TYPE_TEXT -> {
                if (text == null) {
                    throw new IllegalArgumentException("文本内容不能为空");
                }
                if (imageUrl != null) {
                    throw new IllegalArgumentException("文本内容片段不能包含图片地址");
                }
            }
            case TYPE_IMAGE_URL -> {
                if (imageUrl == null || imageUrl.isBlank()) {
                    throw new IllegalArgumentException("图片地址不能为空");
                }
                if (text != null) {
                    throw new IllegalArgumentException("图片内容片段不能包含文本内容");
                }
            }
            default -> throw new IllegalArgumentException("不支持的内容片段类型");
        }
    }

    public static LlmContentPart text(String text) {
        return new LlmContentPart(TYPE_TEXT, text, null);
    }

    public static LlmContentPart imageUrl(String imageUrl) {
        return new LlmContentPart(TYPE_IMAGE_URL, null, imageUrl);
    }
}

package com.agenthub.chat.title;

import org.springframework.stereotype.Service;

@Service
public class ConversationTitleService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_FALLBACK_TITLE_LENGTH = 15;
    private static final int MAX_TITLE_SOURCE_LENGTH = 200;
    private static final int MAX_GENERATED_TITLE_LENGTH = 30;

    public String generateFallbackTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return DEFAULT_TITLE;
        }

        String visibleText = removeWhitespace(prompt);
        if (visibleText.isEmpty()) {
            return DEFAULT_TITLE;
        }
        if (visibleText.length() <= MAX_FALLBACK_TITLE_LENGTH) {
            return visibleText;
        }
        return visibleText.substring(0, MAX_FALLBACK_TITLE_LENGTH);
    }

    public String buildTitlePrompt(String prompt) {
        String source = prompt == null ? "" : prompt.strip();
        if (source.length() > MAX_TITLE_SOURCE_LENGTH) {
            source = source.substring(0, MAX_TITLE_SOURCE_LENGTH);
        }
        return """
                请根据下面用户首次发送的内容生成一个简短中文会话标题。
                要求：不超过 20 个字；不要使用引号；不要解释。

                用户内容：
                %s
                """.formatted(source);
    }

    public String cleanGeneratedTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_TITLE;
        }
        String cleaned = title.strip()
                .replaceAll("^[\"'“”‘’]+", "")
                .replaceAll("[\"'“”‘’。.!！?？]+$", "")
                .strip();
        if (cleaned.isBlank()) {
            return DEFAULT_TITLE;
        }
        if (cleaned.length() <= MAX_GENERATED_TITLE_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, MAX_GENERATED_TITLE_LENGTH);
    }

    private String removeWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }
}

package com.agenthub.chat.title;

import org.springframework.stereotype.Service;

@Service
public class ConversationTitleService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_FALLBACK_TITLE_LENGTH = 15;

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
        String fallbackTitle = visibleText.substring(0, MAX_FALLBACK_TITLE_LENGTH);
        int conjunctionIndex = fallbackTitle.indexOf("和");
        if (conjunctionIndex > 0) {
            return fallbackTitle.substring(0, conjunctionIndex);
        }
        return fallbackTitle;
    }

    private String removeWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }
}

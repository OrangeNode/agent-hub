package com.agenthub.chat.title;

import org.springframework.stereotype.Service;

@Service
public class ConversationTitleService {

    private static final String DEFAULT_TITLE = "新会话";
    private static final int MAX_FALLBACK_TITLE_LENGTH = 15;
    private static final int MAX_TITLE_SOURCE_LENGTH = 200;
    private static final int MAX_GENERATED_TITLE_LENGTH = 30;

    /**
     * 基于用户首条消息生成本地兜底标题。
     */
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

    /**
     * 构造标题生成提示词，并限制输入长度避免标题任务消耗过多上下文。
     */
    public String buildTitlePrompt(String prompt) {
        String source = prompt == null ? "" : prompt.strip();
        if (source.length() > MAX_TITLE_SOURCE_LENGTH) {
            source = source.substring(0, MAX_TITLE_SOURCE_LENGTH);
        }
        return """
                根据用户首次消息生成一个中文会话标题。

                要求：
                - 不超过 20 个字；
                - 只输出标题，不要解释、不要引号、不要标点；
                - 概括核心意图，避免泛泛表述；
                - 内容不明确时输出：新会话。

                用户消息：
                %s
                """.formatted(source);
    }

    /**
     * 清洗模型生成的标题，去除引号和句末标点并限制最大长度。
     */
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

    /**
     * 按 Unicode code point 移除空白，避免截断代理字符。
     */
    private String removeWhitespace(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        value.codePoints()
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }
}

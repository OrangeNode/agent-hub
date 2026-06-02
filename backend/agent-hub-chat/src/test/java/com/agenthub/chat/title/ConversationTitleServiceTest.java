package com.agenthub.chat.title;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConversationTitleServiceTest {

    private final ConversationTitleService conversationTitleService = new ConversationTitleService();

    @Test
    void shouldFallbackToFirstFifteenVisibleCharacters() {
        String title = conversationTitleService.generateFallbackTitle("请帮我重新整理模型管理和供应商管理接口");

        assertThat(title).isEqualTo("请帮我重新整理模型管理和供应商");
        assertThat(title.length()).isLessThanOrEqualTo(15);
    }

    @Test
    void shouldUseDefaultTitleForBlankPrompt() {
        assertThat(conversationTitleService.generateFallbackTitle("   ")).isEqualTo("新会话");
    }

    @Test
    void shouldRemoveWhitespaceBeforeGeneratingFallbackTitle() {
        assertThat(conversationTitleService.generateFallbackTitle("  模型 管理\n设计  ")).isEqualTo("模型管理设计");
    }
}

package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import com.agenthub.ai.llm.openai.OpenAiChatCompletionsClient;
import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import com.agenthub.ai.llm.openai.OpenAiResponsesClient;
import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {

    @Test
    void shouldCreateOpenAiCompatibleClient() {
        LlmClient client = LlmClientFactory.create("OPENAI_COMPATIBLE");

        assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void shouldCreateAnthropicMessagesClient() {
        LlmClient client = LlmClientFactory.create("ANTHROPIC_MESSAGES");

        assertThat(client).isInstanceOf(AnthropicMessagesClient.class);
    }

    @Test
    void shouldCreateOpenAiChatCompletionsClient() {
        LlmClient client = LlmClientFactory.create("OPENAI_CHAT_COMPLETIONS");

        assertThat(client).isInstanceOf(OpenAiChatCompletionsClient.class);
    }

    @Test
    void shouldCreateOpenAiResponsesClient() {
        LlmClient client = LlmClientFactory.create("OPENAI_RESPONSES");

        assertThat(client).isInstanceOf(OpenAiResponsesClient.class);
    }

    @Test
    void shouldRejectUnsupportedProtocol() {
        assertThatThrownBy(() -> LlmClientFactory.create("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("不支持的模型协议: UNKNOWN");
    }
}

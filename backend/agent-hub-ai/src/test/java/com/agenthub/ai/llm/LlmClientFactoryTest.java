package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import org.junit.jupiter.api.Test;

class LlmClientFactoryTest {

    @Test
    void shouldCreateOpenAiCompatibleClient() {
        LlmClient client = LlmClientFactory.create("OPENAI_COMPATIBLE");

        assertThat(client).isInstanceOf(OpenAiCompatibleClient.class);
    }

    @Test
    void shouldRejectRemovedVendorProtocols() {
        assertThatThrownBy(() -> LlmClientFactory.create("OPENAI_CHAT_COMPLETIONS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_CHAT_COMPLETIONS");
        assertThatThrownBy(() -> LlmClientFactory.create("OPENAI_RESPONSES"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_RESPONSES");
        assertThatThrownBy(() -> LlmClientFactory.create("ANTHROPIC_MESSAGES"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ANTHROPIC_MESSAGES");
    }

    @Test
    void shouldRejectUnsupportedProtocol() {
        assertThatThrownBy(() -> LlmClientFactory.create("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN")
                .hasMessageContaining("OPENAI_COMPATIBLE");
    }
}

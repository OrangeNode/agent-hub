package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicMessagesClientTest {

    private final AnthropicMessagesClient client = new AnthropicMessagesClient();

    @Test
    void shouldParseTextDelta() {
        List<LlmStreamChunk> chunks = client.parseEvent(
                "content_block_delta",
                "{\"delta\":{\"type\":\"text_delta\",\"text\":\"回答\"}}");

        assertThat(chunks).containsExactly(LlmStreamChunk.answer("回答"));
    }

    @Test
    void shouldParseThinkingDelta() {
        List<LlmStreamChunk> chunks = client.parseEvent(
                "content_block_delta",
                "{\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"思考\"}}");

        assertThat(chunks).containsExactly(LlmStreamChunk.reasoning("思考"));
    }

    @Test
    void shouldParseMessageStop() {
        List<LlmStreamChunk> chunks = client.parseEvent("message_stop", "{}");

        assertThat(chunks).containsExactly(LlmStreamChunk.done());
    }

    @Test
    void shouldParseErrorEvent() {
        List<LlmStreamChunk> chunks = client.parseEvent(
                "error",
                "{\"error\":{\"message\":\"失败\"}}");

        assertThat(chunks).containsExactly(LlmStreamChunk.error("失败"));
    }
}

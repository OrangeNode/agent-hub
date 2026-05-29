package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MockLlmClientTest {

    @Test
    void shouldStreamDeterministicChunks() {
        LlmClient client = new MockLlmClient();
        ChatRequest request = new ChatRequest(
                "mock",
                List.of(new ChatMessage("user", "你好"))
        );

        List<ChatChunk> chunks = client.stream(request).collectList().block();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.stream().map(ChatChunk::content).toList())
                .contains("你好，我是 Agent Hub 的模拟助手。");
    }
}

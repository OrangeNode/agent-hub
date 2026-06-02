package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();

    @Test
    void shouldParseDonePayload() {
        List<LlmStreamChunk> chunks = client.parseDataLine("[DONE]");

        assertThat(chunks).containsExactly(LlmStreamChunk.done());
    }

    @Test
    void shouldParseAnswerDelta() {
        List<LlmStreamChunk> chunks = client.parseDataLine("{\"choices\":[{\"delta\":{\"content\":\"回答\"}}]}");

        assertThat(chunks).containsExactly(LlmStreamChunk.answer("回答"));
    }

    @Test
    void shouldParseReasoningAndAnswerDelta() {
        List<LlmStreamChunk> chunks = client.parseDataLine(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\",\"content\":\"回答\"}}]}");

        assertThat(chunks).containsExactly(
                LlmStreamChunk.reasoning("思考"),
                LlmStreamChunk.answer("回答"));
    }

    @Test
    void shouldIgnoreMalformedAndEmptyPayload() {
        assertThat(client.parseDataLine("")).isEmpty();
        assertThat(client.parseDataLine("not-json")).isEmpty();
    }
}

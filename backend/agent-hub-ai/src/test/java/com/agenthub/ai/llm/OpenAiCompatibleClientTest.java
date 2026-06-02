package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.stream.Stream;
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

    @Test
    void shouldAppendChatCompletionsWhenBaseUrlAlreadyContainsV1() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(request("https://api.openai.com/v1"), ignored -> {
        });

        assertThat(capturingClient.capturedRequest.uri())
                .hasToString("https://api.openai.com/v1/chat/completions");
    }

    @Test
    void shouldAppendV1ChatCompletionsWhenBaseUrlDoesNotContainV1() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(request("https://api.openai.com/"), ignored -> {
        });

        assertThat(capturingClient.capturedRequest.uri())
                .hasToString("https://api.openai.com/v1/chat/completions");
    }

    private LlmStreamRequest request(String baseUrl) {
        return new LlmStreamRequest(
                baseUrl,
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "你好")),
                null,
                false);
    }

    private static class CapturingOpenAiClient extends OpenAiCompatibleClient {

        private HttpRequest capturedRequest;

        @Override
        protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
            this.capturedRequest = request;
            return new TestStreamResponse(Stream.of("data: [DONE]"));
        }
    }

    private record TestStreamResponse(Stream<String> body) implements HttpResponse<Stream<String>> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public java.util.Optional<HttpResponse<Stream<String>>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (left, right) -> true);
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return null;
        }

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }
}

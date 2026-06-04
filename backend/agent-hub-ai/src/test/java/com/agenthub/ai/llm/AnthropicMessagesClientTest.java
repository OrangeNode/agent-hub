package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AnthropicMessagesClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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

    @Test
    void shouldAppendMessagesWhenBaseUrlAlreadyContainsV1() {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();

        capturingClient.stream(request("https://api.anthropic.com/v1", null), ignored -> {
        });

        assertThat(capturingClient.capturedRequest.uri())
                .hasToString("https://api.anthropic.com/v1/messages");
    }

    @Test
    void shouldAppendV1MessagesWhenBaseUrlDoesNotContainV1() {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();

        capturingClient.stream(request("https://api.anthropic.com/", null), ignored -> {
        });

        assertThat(capturingClient.capturedRequest.uri())
                .hasToString("https://api.anthropic.com/v1/messages");
    }

    @Test
    void shouldUseDefaultMaxTokensWhenRequestValueIsNull() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();

        capturingClient.stream(request("https://api.anthropic.com", null), ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
    }

    @Test
    void shouldMoveSystemMessageToTopLevelSystemField() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(
                        new LlmMessage("system", "系统提示"),
                        new LlmMessage("user", "你好"),
                        new LlmMessage("assistant", "好的")),
                1024,
                false);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("system").asText()).isEqualTo("系统提示");
        assertThat(body.path("messages")).hasSize(2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").get(1).path("role").asText()).isEqualTo("assistant");
    }

    @Test
    void shouldEnableThinkingWhenReasoningRequested() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "你好")),
                4096,
                true);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("thinking").path("budget_tokens").asInt()).isEqualTo(1024);
    }

    private LlmStreamRequest request(String baseUrl, Integer maxOutputTokens) {
        return new LlmStreamRequest(
                baseUrl,
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "你好")),
                maxOutputTokens,
                false);
    }

    private static String readBody(HttpRequest request) {
        ByteArraySubscriber subscriber = new ByteArraySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return subscriber.body();
    }

    private static class CapturingAnthropicClient extends AnthropicMessagesClient {

        private HttpRequest capturedRequest;

        @Override
        protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
            this.capturedRequest = request;
            return new TestStreamResponse(Stream.of("event: message_stop", "data: {}"));
        }
    }

    private static class ByteArraySubscriber implements Flow.Subscriber<ByteBuffer> {

        private final CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            outputStream.writeBytes(bytes);
        }

        @Override
        public void onError(Throwable throwable) {
            bodyFuture.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            bodyFuture.complete(outputStream.toString(StandardCharsets.UTF_8));
        }

        private String body() {
            return bodyFuture.join();
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

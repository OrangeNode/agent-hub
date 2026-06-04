package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
    void shouldParseVllmReasoningDelta() {
        List<LlmStreamChunk> chunks = client.parseDataLine(
                "{\"choices\":[{\"delta\":{\"reasoning\":\"思考\",\"content\":\"回答\"}}]}");

        assertThat(chunks).containsExactly(
                LlmStreamChunk.reasoning("思考"),
                LlmStreamChunk.answer("回答"));
    }

    @Test
    void shouldParseThinkTagFromAnswerContent() {
        List<LlmStreamChunk> chunks = client.parseDataLine(
                "{\"choices\":[{\"delta\":{\"content\":\"<think>思考</think>回答\"}}]}");

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

    @Test
    void shouldEnableThinkingWhenReasoningRequested() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(reasoningRequest("https://dashscope.aliyuncs.com/compatible-mode/v1"), ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("enable_thinking").asBoolean()).isTrue();
    }

    @Test
    void shouldPassChatTemplateThinkingArgsWhenReasoningRequested() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(reasoningRequest("http://localhost:3000/v1"), ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isTrue();
    }

    @Test
    void shouldDisableThinkingWhenReasoningNotRequested() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(request("http://localhost:3000/v1"), ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.has("enable_thinking")).isTrue();
        assertThat(body.path("enable_thinking").asBoolean()).isFalse();
        assertThat(body.has("chat_template_kwargs")).isTrue();
        assertThat(body.path("chat_template_kwargs").has("enable_thinking")).isTrue();
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void shouldParseSplitThinkTagFromStreamingContent() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient(
                Stream.of(
                        "data: {\"choices\":[{\"delta\":{\"content\":\"<thi\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"nk>思\"}}]}",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"考</think>回答\"}}]}",
                        "data: [DONE]"));
        List<LlmStreamChunk> chunks = new ArrayList<>();

        capturingClient.stream(request("https://api.openai.com/v1"), chunks::add);

        assertThat(chunks).containsExactly(
                LlmStreamChunk.reasoning("思考"),
                LlmStreamChunk.answer("回答"),
                LlmStreamChunk.done());
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

    private LlmStreamRequest reasoningRequest(String baseUrl) {
        return new LlmStreamRequest(
                baseUrl,
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "你好")),
                null,
                true);
    }

    private static String readBody(HttpRequest request) {
        ByteArraySubscriber subscriber = new ByteArraySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return subscriber.body();
    }

    private static class CapturingOpenAiClient extends OpenAiCompatibleClient {

        private final Stream<String> responseLines;
        private HttpRequest capturedRequest;

        private CapturingOpenAiClient() {
            this(Stream.of("data: [DONE]"));
        }

        private CapturingOpenAiClient(Stream<String> responseLines) {
            this.responseLines = responseLines;
        }

        @Override
        protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
            this.capturedRequest = request;
            return new TestStreamResponse(responseLines);
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

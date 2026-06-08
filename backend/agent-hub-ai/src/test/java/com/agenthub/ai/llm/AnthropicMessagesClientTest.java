package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.llm.anthropic.AnthropicMessagesClient;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
    void shouldParseToolUseStartWithInput() {
        List<LlmStreamChunk> chunks = client.parseEvent(
                "content_block_start",
                """
                        {"index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"exec","input":{"command":"pwd"}}}
                        """);

        assertThat(chunks).containsExactly(LlmStreamChunk.toolCall(new LlmToolCall(
                "toolu_1",
                "exec",
                Map.of("command", "pwd"))));
    }

    @Test
    void shouldAssembleToolUseInputJsonDeltaFromStream() {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient(
                Stream.of(
                        "event: content_block_start",
                        "data: {\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"exec\",\"input\":{}}}",
                        "",
                        "event: content_block_delta",
                        "data: {\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"command\\\"\"}}",
                        "",
                        "event: content_block_delta",
                        "data: {\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\":\\\"python3 main.py\\\"}\"}}",
                        "",
                        "event: content_block_stop",
                        "data: {\"index\":0}",
                        "",
                        "event: message_stop",
                        "data: {}"));
        List<LlmStreamChunk> chunks = new java.util.ArrayList<>();

        capturingClient.stream(request("https://api.anthropic.com/v1", null), chunks::add);

        assertThat(chunks).containsExactly(
                LlmStreamChunk.toolCall(new LlmToolCall(
                        "toolu_1",
                        "exec",
                        Map.of("command", "python3 main.py"))),
                LlmStreamChunk.done());
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

    @Test
    void shouldReturnErrorChunkWhenMessageContainsImageUrlPart() {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.user(List.of(
                        LlmContentPart.text("分析图片"),
                        LlmContentPart.imageUrl("https://example.com/cat.png")
                ))),
                1024,
                false);
        List<LlmStreamChunk> chunks = new java.util.ArrayList<>();

        capturingClient.stream(request, chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).type()).isEqualTo(LlmStreamChunkType.ERROR);
        assertThat(chunks.get(0).errorMessage()).contains("Anthropic Messages 暂不支持 URL 图片输入");
    }

    @Test
    void shouldSerializeToolDefinitionsWhenPresent() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmToolDefinition tool = new LlmToolDefinition(
                "exec",
                "鎵ц鍛戒护",
                Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")))
        );
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "杩愯鑴氭湰")),
                1024,
                false,
                List.of(tool));

        capturingClient.stream(request, ignored -> {
        });

        JsonNode toolNode = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("tools")
                .get(0);
        assertThat(toolNode.path("name").asText()).isEqualTo("exec");
        assertThat(toolNode.path("input_schema").path("properties").path("command").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void shouldSerializeToolResultMessage() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.tool("toolu_1", "exec", "鎵ц瀹屾垚")),
                1024,
                false);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode message = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("messages")
                .get(0);
        assertThat(message.path("role").asText()).isEqualTo("user");
        JsonNode block = message.path("content").get(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_result");
        assertThat(block.path("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(block.path("content").asText()).isEqualTo("鎵ц瀹屾垚");
    }

    @Test
    void shouldSerializeAssistantToolUseMessage() throws Exception {
        CapturingAnthropicClient capturingClient = new CapturingAnthropicClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "https://api.anthropic.com",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.assistantToolCalls(List.of(new LlmToolCall(
                        "toolu_1",
                        "exec",
                        Map.of("command", "python3 main.py")
                )))),
                1024,
                false);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode block = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("messages")
                .get(0)
                .path("content")
                .get(0);
        assertThat(block.path("type").asText()).isEqualTo("tool_use");
        assertThat(block.path("id").asText()).isEqualTo("toolu_1");
        assertThat(block.path("name").asText()).isEqualTo("exec");
        assertThat(block.path("input").path("command").asText()).isEqualTo("python3 main.py");
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

        private final Stream<String> responseLines;
        private HttpRequest capturedRequest;

        private CapturingAnthropicClient() {
            this(Stream.of("event: message_stop", "data: {}"));
        }

        private CapturingAnthropicClient(Stream<String> responseLines) {
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

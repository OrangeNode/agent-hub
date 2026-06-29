package com.agenthub.ai.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.ai.llm.openai.OpenAiCompatibleClient;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern MODEL_SAFE_TOOL_NAME = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

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
    void shouldIncludeUpstreamErrorBodyWhenStreamRequestFails() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient(
                500,
                Stream.of("{\"error\":{\"message\":\"context length exceeded\"}}"));
        List<LlmStreamChunk> chunks = new ArrayList<>();

        capturingClient.stream(request("https://api.openai.com/v1"), chunks::add);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().type()).isEqualTo(LlmStreamChunkType.ERROR);
        assertThat(chunks.getFirst().errorMessage())
                .contains("HTTP 状态码: 500")
                .contains("context length exceeded");
    }

    @Test
    void shouldRedactPresignedUrlQueryInUpstreamErrorBody() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient(
                500,
                Stream.of("{\"error\":{\"message\":\"failed to download http://minio:9000/bucket/a.png?X-Amz-Signature=secret\"}}"));
        List<LlmStreamChunk> chunks = new ArrayList<>();

        capturingClient.stream(request("https://api.openai.com/v1"), chunks::add);

        assertThat(chunks.getFirst().errorMessage())
                .contains("http://minio:9000/bucket/a.png?<redacted>")
                .doesNotContain("secret");
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
    void shouldParseOpenAiToolCallDelta() {
        List<LlmStreamChunk> chunks = client.parseDataLine("""
                {"choices":[{"delta":{"tool_calls":[{"id":"call_1","type":"function","function":{"name":"exec","arguments":"{\\"command\\":\\"python3 main.py\\"}"}}]}}]}
                """);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().type()).isEqualTo(LlmStreamChunkType.TOOL_CALL);
        assertThat(chunks.getFirst().toolCall().id()).isEqualTo("call_1");
        assertThat(chunks.getFirst().toolCall().name()).isEqualTo("exec");
        assertThat(chunks.getFirst().toolCall().arguments()).containsEntry("command", "python3 main.py");
    }

    @Test
    void shouldAssembleSplitOpenAiToolCallArgumentsFromStream() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient(
                Stream.of(
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"exec\",\"arguments\":\"{\\\"command\\\"\"}}]}}]}",
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"python3 \"}}]}}]}",
                        "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"main.py\\\"}\"}}]}}]}",
                        "data: [DONE]"));
        List<LlmStreamChunk> chunks = new ArrayList<>();

        capturingClient.stream(request("https://api.openai.com/v1"), chunks::add);

        assertThat(chunks).containsExactly(
                LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "exec",
                        Map.of("command", "python3 main.py"))),
                LlmStreamChunk.done());
    }

    @Test
    void shouldSetRequestTimeoutForStreamingCall() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(request("http://localhost:3000/v1"), ignored -> {
        });

        assertThat(capturingClient.capturedRequest.timeout()).contains(Duration.ofSeconds(60));
    }

    @Test
    void shouldUseApiKeyHeaderWhenAuthTypeIsApiKey() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = requestWithProviderOptions(
                "http://localhost:3000/v1",
                "API_KEY",
                "{}",
                "{}");

        capturingClient.stream(request, ignored -> {
        });

        assertThat(capturingClient.capturedRequest.headers().firstValue("api-key")).contains("test-api-key");
        assertThat(capturingClient.capturedRequest.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void shouldApplyExtraHeadersAndBodyWithoutOverridingCoreFields() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = requestWithProviderOptions(
                "http://localhost:3000/v1",
                "BEARER",
                "{\"X-Trace\":\"trace-1\"}",
                "{\"temperature\":0.7,\"stream\":false,\"model\":\"override-model\"}");

        capturingClient.stream(request, ignored -> {
        });

        assertThat(capturingClient.capturedRequest.headers().firstValue("X-Trace")).contains("trace-1");
        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("test-model");
    }

    @Test
    void shouldSerializeMultimodalUserMessageContentParts() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmMessage message = LlmMessage.user(List.of(
                LlmContentPart.text("分析图片"),
                LlmContentPart.imageUrl("https://example.com/cat.png")
        ));
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(message),
                null,
                false);

        capturingClient.stream(request, ignored -> {
        });

        String bodyText = readBody(capturingClient.capturedRequest);
        JsonNode content = OBJECT_MAPPER.readTree(bodyText).path("messages").get(0).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("type").asText()).isEqualTo("text");
        assertThat(content.get(0).path("text").asText()).isEqualTo("分析图片");
        assertThat(content.get(1).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).path("image_url").path("url").asText())
                .isEqualTo("https://example.com/cat.png");
        assertThat(bodyText).contains("\"type\":\"image_url\"");
        assertThat(bodyText).contains("https://example.com/cat.png");
    }

    @Test
    void shouldRejectInvalidContentParts() {
        assertThatThrownBy(() -> LlmContentPart.text(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文本内容不能为空");
        assertThatThrownBy(() -> LlmContentPart.imageUrl(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片地址不能为空");
        assertThatThrownBy(() -> LlmContentPart.imageUrl(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("图片地址不能为空");
        assertThatThrownBy(() -> new LlmContentPart(null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的内容片段类型");
        assertThatThrownBy(() -> new LlmContentPart("audio", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的内容片段类型");
    }

    @Test
    void shouldKeepTextOnlyMessageContentAsString() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "你好")),
                null,
                false);

        capturingClient.stream(request, ignored -> {
        });

        String bodyText = readBody(capturingClient.capturedRequest);
        assertThat(bodyText).contains("\"content\":\"你好\"");
        assertThat(bodyText).doesNotContain("\"content\":[");
    }

    @Test
    void shouldSerializeToolDefinitionsWhenPresent() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmToolDefinition tool = new LlmToolDefinition(
                "exec",
                "执行命令",
                Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")))
        );
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "运行脚本")),
                null,
                false,
                List.of(tool));

        capturingClient.stream(request, ignored -> {
        });

        JsonNode body = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest));
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        JsonNode function = body.path("tools").get(0).path("function");
        assertThat(function.path("name").asText()).isEqualTo("exec");
        assertThat(function.path("parameters").path("properties").path("command").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void shouldSerializeUnsafeToolDefinitionNameAsModelSafeName() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(requestWithTool("sandbox.exec"), ignored -> {
        });

        JsonNode function = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("tools")
                .get(0)
                .path("function");
        String name = function.path("name").asText();
        assertThat(name).isNotEqualTo("sandbox.exec");
        assertThat(name).doesNotContain(".");
        assertThat(MODEL_SAFE_TOOL_NAME.matcher(name).matches()).isTrue();
    }

    @Test
    void shouldKeepSafeToolDefinitionNameUnchanged() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();

        capturingClient.stream(requestWithTool("exec"), ignored -> {
        });

        String name = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("tools")
                .get(0)
                .path("function")
                .path("name")
                .asText();
        assertThat(name).isEqualTo("exec");
    }

    @Test
    void shouldSerializeToolResultMessage() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.tool("call_1", "exec", "执行完成")),
                null,
                false);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode message = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("messages")
                .get(0);
        assertThat(message.path("role").asText()).isEqualTo("tool");
        assertThat(message.path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(message.path("content").asText()).isEqualTo("执行完成");
    }

    @Test
    void shouldSerializeAssistantToolCallMessage() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.assistantToolCalls(List.of(new LlmToolCall(
                        "call_1",
                        "exec",
                        Map.of("command", "python3 main.py")
                )))),
                null,
                false);

        capturingClient.stream(request, ignored -> {
        });

        JsonNode toolCall = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("messages")
                .get(0)
                .path("tool_calls")
                .get(0);
        assertThat(toolCall.path("id").asText()).isEqualTo("call_1");
        assertThat(toolCall.path("function").path("name").asText()).isEqualTo("exec");
        assertThat(toolCall.path("function").path("arguments").asText()).contains("python3 main.py");
    }

    @Test
    void shouldSerializeUnsafeAssistantToolCallNameAsModelSafeName() throws Exception {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient();
        LlmStreamRequest request = new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(LlmMessage.assistantToolCalls(List.of(new LlmToolCall(
                        "call_1",
                        "sandbox.exec",
                        Map.of("command", "python3 main.py")
                )))),
                null,
                false,
                List.of(tool("sandbox.exec")));

        capturingClient.stream(request, ignored -> {
        });

        String name = OBJECT_MAPPER.readTree(readBody(capturingClient.capturedRequest))
                .path("messages")
                .get(0)
                .path("tool_calls")
                .get(0)
                .path("function")
                .path("name")
                .asText();
        assertThat(name).isNotEqualTo("sandbox.exec");
        assertThat(MODEL_SAFE_TOOL_NAME.matcher(name).matches()).isTrue();
    }

    @Test
    void shouldRestoreUnsafeToolCallNameFromSplitStreamingAccumulator() {
        CapturingOpenAiClient capturingClient = new CapturingOpenAiClient(request -> {
            String safeName = openAiToolName(request);
            return Stream.of(
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"" + safeName + "\",\"arguments\":\"{\\\"command\\\"\"}}]}}]}",
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"python3 \"}}]}}]}",
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"main.py\\\"}\"}}]}}]}",
                    "data: [DONE]");
        });
        List<LlmStreamChunk> chunks = new ArrayList<>();

        capturingClient.stream(requestWithTool("sandbox.exec"), chunks::add);

        assertThat(chunks).containsExactly(
                LlmStreamChunk.toolCall(new LlmToolCall(
                        "call_1",
                        "sandbox.exec",
                        Map.of("command", "python3 main.py"))),
                LlmStreamChunk.done());
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

    private LlmStreamRequest requestWithProviderOptions(
            String baseUrl,
            String authType,
            String extraHeaders,
            String extraBody
    ) {
        return new LlmStreamRequest(
                baseUrl,
                "test-api-key",
                authType,
                null,
                extraHeaders,
                extraBody,
                "test-model",
                List.of(new LlmMessage("user", "浣犲ソ")),
                null,
                false,
                List.of());
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

    private LlmStreamRequest requestWithTool(String toolName) {
        return new LlmStreamRequest(
                "http://localhost:3000/v1",
                "test-api-key",
                null,
                "test-model",
                List.of(new LlmMessage("user", "运行脚本")),
                null,
                false,
                List.of(tool(toolName)));
    }

    private LlmToolDefinition tool(String name) {
        return new LlmToolDefinition(
                name,
                "执行命令",
                Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")))
        );
    }

    private static String openAiToolName(HttpRequest request) {
        try {
            return OBJECT_MAPPER.readTree(readBody(request))
                    .path("tools")
                    .get(0)
                    .path("function")
                    .path("name")
                    .asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String readBody(HttpRequest request) {
        ByteArraySubscriber subscriber = new ByteArraySubscriber();
        request.bodyPublisher().orElseThrow().subscribe(subscriber);
        return subscriber.body();
    }

    private static class CapturingOpenAiClient extends OpenAiCompatibleClient {

        private final int statusCode;
        private final Function<HttpRequest, Stream<String>> responseLines;
        private HttpRequest capturedRequest;

        private CapturingOpenAiClient() {
            this(Stream.of("data: [DONE]"));
        }

        private CapturingOpenAiClient(Stream<String> responseLines) {
            this(200, responseLines);
        }

        private CapturingOpenAiClient(int statusCode, Stream<String> responseLines) {
            this(statusCode, ignored -> responseLines);
        }

        private CapturingOpenAiClient(Function<HttpRequest, Stream<String>> responseLines) {
            this(200, responseLines);
        }

        private CapturingOpenAiClient(int statusCode, Function<HttpRequest, Stream<String>> responseLines) {
            this.statusCode = statusCode;
            this.responseLines = responseLines;
        }

        @Override
        protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
            this.capturedRequest = request;
            return new TestStreamResponse(statusCode, responseLines.apply(request));
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

    private record TestStreamResponse(int statusCode, Stream<String> body) implements HttpResponse<Stream<String>> {

        @Override
        public int statusCode() {
            return statusCode;
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

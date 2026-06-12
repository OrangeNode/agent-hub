package com.agenthub.ai.llm.openai;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmContentPart;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.agenthub.ai.llm.tool.LlmToolCall;
import com.agenthub.ai.llm.tool.LlmToolDefinition;
import com.agenthub.ai.llm.tool.LlmToolNameMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class OpenAiCompatibleClient implements LlmClient {

    private static final String THINK_OPEN_TAG = "<think>";
    private static final String THINK_CLOSE_TAG = "</think>";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration STREAM_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAiCompatibleClient() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    public OpenAiCompatibleClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 发送 OpenAI Chat Completions 兼容协议请求，并输出统一流式 chunk。
     */
    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        try {
            LlmToolNameMapper toolNameMapper = LlmToolNameMapper.from(request.tools());
            HttpResponse<Stream<String>> response = send(buildRequest(request, toolNameMapper));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求失败，HTTP 状态码: " + response.statusCode()));
                return;
            }
            ThinkTagSplitter thinkTagSplitter = new ThinkTagSplitter();
            ToolCallAccumulator toolCallAccumulator = new ToolCallAccumulator(toolNameMapper);
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()).trim())
                        .flatMap(payload -> parseDataLine(payload, toolCallAccumulator, toolNameMapper).stream())
                        .forEach(chunk -> emitChunk(chunk, thinkTagSplitter, chunkConsumer));
                thinkTagSplitter.flush(chunkConsumer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求被中断"));
        } catch (Exception e) {
            chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求失败: " + e.getMessage()));
        }
    }

    /**
     * 处理标准 chunk 和 think 标签拆分后的推理 chunk。
     */
    private void emitChunk(
            LlmStreamChunk chunk,
            ThinkTagSplitter thinkTagSplitter,
            Consumer<LlmStreamChunk> chunkConsumer
    ) {
        switch (chunk.type()) {
            case ANSWER_DELTA -> thinkTagSplitter.accept(defaultString(chunk.content()), chunkConsumer);
            case DONE, ERROR -> {
                thinkTagSplitter.flush(chunkConsumer);
                chunkConsumer.accept(chunk);
            }
            case REASONING_DELTA, TOOL_CALL, USAGE -> chunkConsumer.accept(chunk);
        }
    }

    /**
     * 解析单行 data 负载，兼容不同 OpenAI-like 服务的推理字段。
     */
    public List<LlmStreamChunk> parseDataLine(String payload) {
        LlmToolNameMapper toolNameMapper = LlmToolNameMapper.from(List.of());
        return parseDataLine(payload, new ToolCallAccumulator(toolNameMapper), toolNameMapper);
    }

    private List<LlmStreamChunk> parseDataLine(
            String payload,
            ToolCallAccumulator toolCallAccumulator,
            LlmToolNameMapper toolNameMapper
    ) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        if ("[DONE]".equals(payload.trim())) {
            List<LlmStreamChunk> chunks = new ArrayList<>(toolCallAccumulator.flush());
            chunks.add(LlmStreamChunk.done());
            return chunks;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode message = choice.path("message");

            List<LlmStreamChunk> chunks = new ArrayList<>();
            addToolCallChunks(chunks, delta.path("tool_calls"), toolCallAccumulator, toolNameMapper);
            addTextChunk(chunks, firstText(delta.path("reasoning"),
                    delta.path("reasoning_content"),
                    message.path("reasoning"),
                    message.path("reasoning_content"),
                    choice.path("reasoning"),
                    choice.path("reasoning_content"),
                    root.path("reasoning"),
                    root.path("reasoning_content")), true);
            addAnswerChunks(chunks, delta.path("content"));
            return chunks;
        } catch (Exception e) {
            return List.of();
        }
    }

    protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    private HttpRequest buildRequest(LlmStreamRequest request, LlmToolNameMapper toolNameMapper) throws IOException {
        String body = OBJECT_MAPPER.writeValueAsString(buildRequestBody(request, toolNameMapper));
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(request.baseUrl(), "/chat/completions")))
                .timeout(STREAM_REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + request.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * 构造兼容协议请求体，推理开关同时写入常见扩展字段。
     */
    private Map<String, Object> buildRequestBody(LlmStreamRequest request, LlmToolNameMapper toolNameMapper) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", toMessages(request.messages(), toolNameMapper));
        body.put("stream", true);
        if (request.maxOutputTokens() != null) {
            body.put("max_tokens", request.maxOutputTokens());
        }
        body.put("enable_thinking", request.reasoningRequested());
        body.put("chat_template_kwargs", Map.of("enable_thinking", request.reasoningRequested()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(tool -> toOpenAiTool(tool, toolNameMapper)).toList());
            body.put("tool_choice", "auto");
        }
        return body;
    }

    /**
     * 普通回答内容可能内嵌 think 标签，需要在解析阶段拆成推理和回答片段。
     */
    private void addAnswerChunks(List<LlmStreamChunk> chunks, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isEmpty()) {
            return;
        }
        ThinkTagSplitter splitter = new ThinkTagSplitter();
        splitter.accept(node.asText(), chunks::add);
        splitter.flush(chunks::add);
    }

    private List<Map<String, Object>> toMessages(List<LlmMessage> messages, LlmToolNameMapper toolNameMapper) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(message -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", message.hasParts() ? toContentParts(message.parts()) : message.content());
                    if (message.hasToolCalls()) {
                        item.put("tool_calls", message.toolCalls().stream()
                                .map(toolCall -> toOpenAiToolCall(toolCall, toolNameMapper))
                                .toList());
                    }
                    if ("tool".equals(message.role())) {
                        item.put("tool_call_id", message.toolCallId());
                    }
                    return item;
                })
                .toList();
    }

    private Map<String, Object> toOpenAiToolCall(LlmToolCall toolCall, LlmToolNameMapper toolNameMapper) {
        return Map.of(
                "id", toolCall.id(),
                "type", "function",
                "function", Map.of(
                        "name", toolNameMapper.toModelName(toolCall.name()),
                        "arguments", writeJson(toolCall.arguments())
                )
        );
    }

    private Map<String, Object> toOpenAiTool(LlmToolDefinition tool, LlmToolNameMapper toolNameMapper) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", toolNameMapper.toModelName(tool.name()),
                        "description", tool.description(),
                        "parameters", tool.parameters()
                )
        );
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private List<Map<String, Object>> toContentParts(List<LlmContentPart> parts) {
        return parts.stream()
                .map(part -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", part.type());
                    switch (part.type()) {
                        case LlmContentPart.TYPE_TEXT -> item.put("text", part.text());
                        case LlmContentPart.TYPE_IMAGE_URL -> item.put("image_url", Map.of("url", part.imageUrl()));
                        default -> throw new IllegalArgumentException("不支持的内容片段类型");
                    }
                    return item;
                })
                .toList();
    }

    private void addTextChunk(List<LlmStreamChunk> chunks, JsonNode node, boolean reasoning) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isEmpty()) {
            return;
        }
        chunks.add(reasoning ? LlmStreamChunk.reasoning(node.asText()) : LlmStreamChunk.answer(node.asText()));
    }

    private void addToolCallChunks(
            List<LlmStreamChunk> chunks,
            JsonNode toolCalls,
            ToolCallAccumulator toolCallAccumulator,
            LlmToolNameMapper toolNameMapper
    ) {
        if (toolCalls == null || !toolCalls.isArray()) {
            return;
        }
        for (JsonNode toolCall : toolCalls) {
            JsonNode function = toolCall.path("function");
            String name = function.path("name").asText(null);
            if (toolCall.has("index")) {
                toolCallAccumulator.accept(toolCall).ifPresent(chunks::add);
                continue;
            }
            if (name == null || name.isBlank()) {
                continue;
            }
            chunks.add(LlmStreamChunk.toolCall(new LlmToolCall(
                    toolCall.path("id").asText(null),
                    toolNameMapper.toInternalName(name),
                    parseArguments(function.path("arguments").asText(""))
            )));
        }
    }

    private Map<String, Object> parseArguments(String arguments) {
        return parseArgumentsIfComplete(arguments).orElseGet(Map::of);
    }

    private Optional<Map<String, Object>> parseArgumentsIfComplete(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Optional.of(Map.of());
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(arguments, new TypeReference<>() {
            }));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private JsonNode firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText().isEmpty()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 兼容传入根地址或 /v1 地址两种配置方式。
     */
    private String endpoint(String baseUrl, String v1Path) {
        String normalizedBaseUrl = trimTrailingSlash(baseUrl);
        if (normalizedBaseUrl.endsWith("/v1")) {
            return normalizedBaseUrl + v1Path;
        }
        return normalizedBaseUrl + "/v1" + v1Path;
    }

    private String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI Compatible baseUrl 不能为空");
        }
        return baseUrl.replaceAll("/+$", "");
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private class ToolCallAccumulator {

        private final Map<String, PendingToolCall> pendingToolCalls = new LinkedHashMap<>();
        private final LlmToolNameMapper toolNameMapper;

        private ToolCallAccumulator(LlmToolNameMapper toolNameMapper) {
            this.toolNameMapper = toolNameMapper;
        }

        private Optional<LlmStreamChunk> accept(JsonNode toolCall) {
            JsonNode function = toolCall.path("function");
            String key = toolCall.path("index").asText();
            PendingToolCall pendingToolCall = pendingToolCalls.computeIfAbsent(key, ignored -> new PendingToolCall());
            pendingToolCall.append(
                    textOrNull(toolCall.path("id")),
                    textOrNull(function.path("name")),
                    function.has("arguments") ? function.path("arguments").asText("") : null
            );

            if (pendingToolCall.name == null || !pendingToolCall.hasArguments || pendingToolCall.arguments.isEmpty()) {
                return Optional.empty();
            }
            Optional<Map<String, Object>> arguments = parseArgumentsIfComplete(pendingToolCall.arguments.toString());
            if (arguments.isEmpty()) {
                return Optional.empty();
            }
            pendingToolCalls.remove(key);
            return Optional.of(LlmStreamChunk.toolCall(new LlmToolCall(
                    pendingToolCall.id,
                    toolNameMapper.toInternalName(pendingToolCall.name),
                    arguments.get()
            )));
        }

        private List<LlmStreamChunk> flush() {
            List<LlmStreamChunk> chunks = new ArrayList<>();
            pendingToolCalls.values().stream()
                    .filter(pendingToolCall -> pendingToolCall.name != null)
                    .map(pendingToolCall -> LlmStreamChunk.toolCall(new LlmToolCall(
                            pendingToolCall.id,
                            toolNameMapper.toInternalName(pendingToolCall.name),
                            parseArguments(pendingToolCall.arguments.toString())
                    )))
                    .forEach(chunks::add);
            pendingToolCalls.clear();
            return chunks;
        }
    }

    private static class PendingToolCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
        private boolean hasArguments;

        private void append(String id, String name, String arguments) {
            if (id != null) {
                this.id = id;
            }
            if (name != null) {
                this.name = name;
            }
            if (arguments != null) {
                this.hasArguments = true;
                this.arguments.append(arguments);
            }
        }
    }

    private static class ThinkTagSplitter {

        private final StringBuilder buffer = new StringBuilder();
        private boolean reasoning;

        /**
         * 接收增量文本并尽可能拆出完整的推理或回答片段。
         */
        private void accept(String content, Consumer<LlmStreamChunk> chunkConsumer) {
            if (content == null || content.isEmpty()) {
                return;
            }
            buffer.append(content);
            drain(chunkConsumer, false);
        }

        /**
         * 流结束时输出缓冲区内未闭合标签前后的剩余文本。
         */
        private void flush(Consumer<LlmStreamChunk> chunkConsumer) {
            drain(chunkConsumer, true);
        }

        private void drain(Consumer<LlmStreamChunk> chunkConsumer, boolean flush) {
            while (!buffer.isEmpty()) {
                if (reasoning) {
                    if (!drainReasoning(chunkConsumer, flush)) {
                        return;
                    }
                    continue;
                }
                if (!drainAnswer(chunkConsumer, flush)) {
                    return;
                }
            }
        }

        private boolean drainAnswer(Consumer<LlmStreamChunk> chunkConsumer, boolean flush) {
            int openIndex = buffer.indexOf(THINK_OPEN_TAG);
            if (openIndex >= 0) {
                emit(buffer.substring(0, openIndex), false, chunkConsumer);
                buffer.delete(0, openIndex + THINK_OPEN_TAG.length());
                reasoning = true;
                return true;
            }
            return emitBufferedText(chunkConsumer, flush, THINK_OPEN_TAG.length() - 1, false);
        }

        private boolean drainReasoning(Consumer<LlmStreamChunk> chunkConsumer, boolean flush) {
            int closeIndex = buffer.indexOf(THINK_CLOSE_TAG);
            if (closeIndex >= 0) {
                emit(buffer.substring(0, closeIndex), true, chunkConsumer);
                buffer.delete(0, closeIndex + THINK_CLOSE_TAG.length());
                reasoning = false;
                return true;
            }
            return emitBufferedText(chunkConsumer, flush, THINK_CLOSE_TAG.length() - 1, true);
        }

        /**
         * 保留标签长度减一的尾部文本，避免跨 chunk 的标签被提前输出。
         */
        private boolean emitBufferedText(
                Consumer<LlmStreamChunk> chunkConsumer,
                boolean flush,
                int keepLength,
                boolean asReasoning
        ) {
            int emitLength = flush ? buffer.length() : buffer.length() - keepLength;
            if (emitLength <= 0) {
                return false;
            }
            String text = buffer.substring(0, emitLength);
            buffer.delete(0, emitLength);
            emit(text, asReasoning, chunkConsumer);
            return !buffer.isEmpty();
        }

        private void emit(String text, boolean asReasoning, Consumer<LlmStreamChunk> chunkConsumer) {
            if (!text.isEmpty()) {
                chunkConsumer.accept(asReasoning ? LlmStreamChunk.reasoning(text) : LlmStreamChunk.answer(text));
            }
        }
    }
}

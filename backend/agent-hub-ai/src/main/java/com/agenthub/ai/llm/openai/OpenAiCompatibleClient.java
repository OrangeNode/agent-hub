package com.agenthub.ai.llm.openai;

import com.agenthub.ai.llm.LlmClient;
import com.agenthub.ai.llm.LlmMessage;
import com.agenthub.ai.llm.LlmStreamChunk;
import com.agenthub.ai.llm.LlmStreamRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class OpenAiCompatibleClient implements LlmClient {

    private static final String THINK_OPEN_TAG = "<think>";
    private static final String THINK_CLOSE_TAG = "</think>";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public OpenAiCompatibleClient() {
        this(HttpClient.newHttpClient());
    }

    public OpenAiCompatibleClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        try {
            HttpResponse<Stream<String>> response = send(buildRequest(request));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求失败，HTTP 状态码: " + response.statusCode()));
                return;
            }
            ThinkTagSplitter thinkTagSplitter = new ThinkTagSplitter();
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()).trim())
                        .flatMap(payload -> parseDataLine(payload).stream())
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
            case REASONING_DELTA, USAGE -> chunkConsumer.accept(chunk);
        }
    }

    public List<LlmStreamChunk> parseDataLine(String payload) {
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        if ("[DONE]".equals(payload.trim())) {
            return List.of(LlmStreamChunk.done());
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            JsonNode choice = root.path("choices").path(0);
            JsonNode delta = choice.path("delta");
            JsonNode message = choice.path("message");

            List<LlmStreamChunk> chunks = new ArrayList<>();
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

    private HttpRequest buildRequest(LlmStreamRequest request) throws IOException {
        String body = OBJECT_MAPPER.writeValueAsString(buildRequestBody(request));
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(request.baseUrl(), "/chat/completions")))
                .header("Authorization", "Bearer " + request.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private Map<String, Object> buildRequestBody(LlmStreamRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", toMessages(request.messages()));
        body.put("stream", true);
        if (request.maxOutputTokens() != null) {
            body.put("max_tokens", request.maxOutputTokens());
        }
        body.put("enable_thinking", request.reasoningRequested());
        body.put("chat_template_kwargs", Map.of("enable_thinking", request.reasoningRequested()));
        return body;
    }

    private void addAnswerChunks(List<LlmStreamChunk> chunks, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || node.asText().isEmpty()) {
            return;
        }
        ThinkTagSplitter splitter = new ThinkTagSplitter();
        splitter.accept(node.asText(), chunks::add);
        splitter.flush(chunks::add);
    }

    private List<Map<String, String>> toMessages(List<LlmMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .map(message -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", message.content());
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

    private JsonNode firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText().isEmpty()) {
                return node;
            }
        }
        return null;
    }

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

    private static class ThinkTagSplitter {

        private final StringBuilder buffer = new StringBuilder();
        private boolean reasoning;

        private void accept(String content, Consumer<LlmStreamChunk> chunkConsumer) {
            if (content == null || content.isEmpty()) {
                return;
            }
            buffer.append(content);
            drain(chunkConsumer, false);
        }

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

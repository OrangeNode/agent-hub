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
            try (Stream<String> lines = response.body()) {
                lines.filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring("data:".length()).trim())
                        .flatMap(payload -> parseDataLine(payload).stream())
                        .forEach(chunkConsumer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求被中断"));
        } catch (Exception e) {
            chunkConsumer.accept(LlmStreamChunk.error("OpenAI Compatible 流式请求失败: " + e.getMessage()));
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

            List<LlmStreamChunk> chunks = new ArrayList<>();
            addTextChunk(chunks, firstText(delta.path("reasoning_content"),
                    choice.path("reasoning_content"), root.path("reasoning_content")), true);
            addTextChunk(chunks, delta.path("content"), false);
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
                .uri(URI.create(trimTrailingSlash(request.baseUrl()) + "/v1/chat/completions"))
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
        return body;
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

    private String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OpenAI Compatible baseUrl 不能为空");
        }
        return baseUrl.replaceAll("/+$", "");
    }
}

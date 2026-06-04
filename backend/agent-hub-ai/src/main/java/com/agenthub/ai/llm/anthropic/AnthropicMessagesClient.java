package com.agenthub.ai.llm.anthropic;

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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class AnthropicMessagesClient implements LlmClient {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String DEFAULT_API_VERSION = "2023-06-01";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public AnthropicMessagesClient() {
        this(HttpClient.newHttpClient());
    }

    public AnthropicMessagesClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 发送 Anthropic Messages 流式请求，并输出统一流式 chunk。
     */
    @Override
    public void stream(LlmStreamRequest request, Consumer<LlmStreamChunk> chunkConsumer) {
        try {
            rejectUnsupportedContentParts(request.messages());
            HttpResponse<Stream<String>> response = send(buildRequest(request));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                chunkConsumer.accept(LlmStreamChunk.error("Anthropic Messages 流式请求失败，HTTP 状态码: " + response.statusCode()));
                return;
            }
            consumeSse(response.body(), chunkConsumer);
        } catch (IllegalArgumentException e) {
            chunkConsumer.accept(LlmStreamChunk.error(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            chunkConsumer.accept(LlmStreamChunk.error("Anthropic Messages 流式请求被中断"));
        } catch (Exception e) {
            chunkConsumer.accept(LlmStreamChunk.error("Anthropic Messages 流式请求失败: " + e.getMessage()));
        }
    }

    private void rejectUnsupportedContentParts(List<LlmMessage> messages) {
        if (messages == null) {
            return;
        }
        boolean hasParts = messages.stream().anyMatch(LlmMessage::hasParts);
        if (hasParts) {
            throw new IllegalArgumentException("Anthropic Messages 暂不支持 URL 图片输入");
        }
    }

    /**
     * 将 Anthropic SSE 事件转换为统一 chunk。
     */
    public List<LlmStreamChunk> parseEvent(String eventName, String payload) {
        if (eventName == null || eventName.isBlank()) {
            return List.of();
        }
        if ("message_stop".equals(eventName)) {
            return List.of(LlmStreamChunk.done());
        }
        if (payload == null || payload.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            if ("error".equals(eventName)) {
                String message = root.path("error").path("message").asText("Anthropic Messages 流式请求失败");
                return List.of(LlmStreamChunk.error(message));
            }
            if (!"content_block_delta".equals(eventName)) {
                return List.of();
            }
            JsonNode delta = root.path("delta");
            String type = delta.path("type").asText();
            if ("text_delta".equals(type)) {
                String text = delta.path("text").asText();
                return text.isEmpty() ? List.of() : List.of(LlmStreamChunk.answer(text));
            }
            if ("thinking_delta".equals(type)) {
                String thinking = delta.path("thinking").asText(delta.path("text").asText());
                return thinking.isEmpty() ? List.of() : List.of(LlmStreamChunk.reasoning(thinking));
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    protected HttpResponse<Stream<String>> send(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
    }

    /**
     * 消费 Anthropic SSE 行协议，按 event/data 组合解析事件。
     */
    private void consumeSse(Stream<String> lines, Consumer<LlmStreamChunk> chunkConsumer) {
        String eventName = null;
        try (Stream<String> closeableLines = lines) {
            Iterator<String> iterator = closeableLines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.isBlank()) {
                    eventName = null;
                    continue;
                }
                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    String payload = line.substring("data:".length()).trim();
                    for (LlmStreamChunk chunk : parseEvent(eventName, payload)) {
                        chunkConsumer.accept(chunk);
                    }
                }
            }
        }
    }

    private HttpRequest buildRequest(LlmStreamRequest request) throws IOException {
        String body = OBJECT_MAPPER.writeValueAsString(buildRequestBody(request));
        return HttpRequest.newBuilder()
                .uri(URI.create(endpoint(request.baseUrl(), "/messages")))
                .header("x-api-key", request.apiKey())
                .header("anthropic-version", apiVersion(request))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    /**
     * 构造 Messages 请求体，system 消息按 Anthropic 协议拆到顶层字段。
     */
    private Map<String, Object> buildRequestBody(LlmStreamRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        int maxTokens = request.maxOutputTokens() == null ? DEFAULT_MAX_TOKENS : request.maxOutputTokens();
        body.put("model", request.model());
        body.put("messages", toMessages(request.messages()));
        body.put("stream", true);
        body.put("max_tokens", maxTokens);
        if (request.reasoningRequested()) {
            body.put("thinking", thinkingConfig(maxTokens));
        }

        String system = systemPrompt(request.messages());
        if (system != null) {
            body.put("system", system);
        }
        return body;
    }

    /**
     * Anthropic 要求 thinking 预算小于输出上限，这里保留至少一个输出 token。
     */
    private Map<String, Object> thinkingConfig(int maxTokens) {
        Map<String, Object> thinking = new LinkedHashMap<>();
        thinking.put("type", "enabled");
        thinking.put("budget_tokens", Math.min(1024, Math.max(1, maxTokens - 1)));
        return thinking;
    }

    private List<Map<String, String>> toMessages(List<LlmMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(message -> !"system".equals(message.role()))
                .map(message -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("role", message.role());
                    item.put("content", message.content());
                    return item;
                })
                .toList();
    }

    /**
     * 合并多条 system 消息，适配 Anthropic 顶层 system 字段。
     */
    private String systemPrompt(List<LlmMessage> messages) {
        if (messages == null) {
            return null;
        }
        String system = messages.stream()
                .filter(message -> "system".equals(message.role()))
                .map(LlmMessage::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse(null);
        return system == null || system.isBlank() ? null : system;
    }

    private String apiVersion(LlmStreamRequest request) {
        if (request.apiVersion() == null || request.apiVersion().isBlank()) {
            return DEFAULT_API_VERSION;
        }
        return request.apiVersion();
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
            throw new IllegalArgumentException("Anthropic Messages baseUrl 不能为空");
        }
        return baseUrl.replaceAll("/+$", "");
    }
}

package com.agenthub.ai.provider.service.impl;

import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.entity.AiProvider;
import com.agenthub.ai.provider.service.AiProviderConnectivityTester;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class HttpAiProviderConnectivityTester implements AiProviderConnectivityTester {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    public HttpAiProviderConnectivityTester() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build());
    }

    public HttpAiProviderConnectivityTester(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public AiProviderConnectionTestResult test(AiProvider provider) {
        if (provider == null) {
            return failed("供应商配置不能为空", null);
        }
        if (!hasText(provider.getBaseUrl())) {
            return failed("基础地址不能为空", null);
        }
        if (!hasText(provider.getApiKeyCipher())) {
            return failed("API Key 未配置", null);
        }
        try {
            HttpResponse<Void> response = httpClient.send(buildRequest(provider), HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                return new AiProviderConnectionTestResult(true, "连接成功", statusCode);
            }
            return failed("连接失败，HTTP 状态码: " + statusCode, statusCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed("连接测试被中断", null);
        } catch (Exception e) {
            return failed("连接失败: " + e.getMessage(), null);
        }
    }

    private HttpRequest buildRequest(AiProvider provider) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(modelsEndpoint(provider.getBaseUrl())))
                .timeout(REQUEST_TIMEOUT)
                .GET();
        applyAuthHeaders(builder, provider);
        applyExtraHeaders(builder, provider.getExtraHeaders());
        return builder.build();
    }

    private void applyAuthHeaders(HttpRequest.Builder builder, AiProvider provider) {
        String apiKey = decryptApiKey(provider.getApiKeyCipher());
        if ("API_KEY".equals(provider.getAuthType())) {
            builder.header("api-key", apiKey);
            return;
        }
        builder.header("Authorization", "Bearer " + apiKey);
    }

    private void applyExtraHeaders(HttpRequest.Builder builder, String extraHeaders) throws IOException {
        if (!hasText(extraHeaders) || "{}".equals(extraHeaders.trim())) {
            return;
        }
        JsonNode root = OBJECT_MAPPER.readTree(extraHeaders);
        if (!root.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = root.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isTextual()) {
                builder.header(field.getKey(), field.getValue().asText());
            }
        }
    }

    private String modelsEndpoint(String baseUrl) {
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/v1")) {
            return normalized + "/models";
        }
        return normalized + "/v1/models";
    }

    private String trimTrailingSlash(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }

    private String decryptApiKey(String apiKeyCipher) {
        return apiKeyCipher;
    }

    private AiProviderConnectionTestResult failed(String message, Integer statusCode) {
        return new AiProviderConnectionTestResult(false, message, statusCode);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

package com.agenthub.system.operationlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.system.operationlog.support.OperationLogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationLogSanitizerTest {

    @Test
    void shouldMaskSensitiveFieldsCaseInsensitively() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of(
                "username", "admin",
                "password", "123456",
                "Authorization", "Bearer token",
                "nested", Map.of("secret", "abc")
        ), 4000, new String[]{"token"});

        assertThat(json).contains("\"username\":\"admin\"");
        assertThat(json).contains("\"password\":\"******\"");
        assertThat(json).contains("\"Authorization\":\"******\"");
        assertThat(json).contains("\"secret\":\"******\"");
        assertThat(json).doesNotContain("123456");
        assertThat(json).doesNotContain("Bearer token");
        assertThat(json).doesNotContain("abc");
    }

    @Test
    void shouldMaskCommonSensitiveFieldVariants() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.ofEntries(
                Map.entry("accessToken", "access-token-value"),
                Map.entry("refreshToken", "refresh-token-value"),
                Map.entry("clientSecret", "client-secret-value"),
                Map.entry("apiKey", "api-key-value"),
                Map.entry("api_key", "api-key-snake-value"),
                Map.entry("x-api-key", "api-key-header-value"),
                Map.entry("openaiApiKey", "openai-api-key-value"),
                Map.entry("apiKeyHeader", "api-key-header-name-value"),
                Map.entry("publicKey", "public-key-value"),
                Map.entry("internal_token", "internal-token-value"),
                Map.entry("authorizationHeader", "authorization-header-value"),
                Map.entry("contentType", "application/json"),
                Map.entry("nested", Map.of("my-password", "password-value"))
        ), 4000, new String[0]);

        assertThat(json).contains("\"accessToken\":\"******\"");
        assertThat(json).contains("\"refreshToken\":\"******\"");
        assertThat(json).contains("\"clientSecret\":\"******\"");
        assertThat(json).contains("\"apiKey\":\"******\"");
        assertThat(json).contains("\"api_key\":\"******\"");
        assertThat(json).contains("\"x-api-key\":\"******\"");
        assertThat(json).contains("\"openaiApiKey\":\"******\"");
        assertThat(json).contains("\"apiKeyHeader\":\"******\"");
        assertThat(json).contains("\"publicKey\":\"public-key-value\"");
        assertThat(json).contains("\"internal_token\":\"******\"");
        assertThat(json).contains("\"authorizationHeader\":\"******\"");
        assertThat(json).contains("\"my-password\":\"******\"");
        assertThat(json).contains("\"contentType\":\"application/json\"");
        assertThat(json)
                .doesNotContain("access-token-value")
                .doesNotContain("refresh-token-value")
                .doesNotContain("client-secret-value")
                .doesNotContain("api-key-value")
                .doesNotContain("api-key-snake-value")
                .doesNotContain("api-key-header-value")
                .doesNotContain("openai-api-key-value")
                .doesNotContain("api-key-header-name-value")
                .doesNotContain("internal-token-value")
                .doesNotContain("authorization-header-value")
                .doesNotContain("password-value");
    }

    @Test
    void shouldTruncateLongJsonAsValidJson() throws Exception {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();

        String json = sanitizer.toSanitizedJson(Map.of("command", "x".repeat(100)), 40, new String[0]);

        assertThat(json.length()).isLessThanOrEqualTo(40);
        assertThat(objectMapper.readTree(json).path("truncated").asBoolean()).isTrue();
    }

    @Test
    void shouldReturnValidJsonWhenMaxLengthCannotFitTruncatedObject() throws Exception {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();
        ObjectMapper objectMapper = new ObjectMapper();

        String json = sanitizer.toSanitizedJson(Map.of("command", "x".repeat(100)), 4, new String[0]);

        assertThat(json.length()).isLessThanOrEqualTo(4);
        objectMapper.readTree(json);
    }

    @Test
    void shouldSerializeJavaTimeValues() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of(
                "createdAt", OffsetDateTime.parse("2026-06-11T10:15:30+08:00")
        ), 4000, new String[0]);

        assertThat(json).isNotEqualTo("\"<unserializable>\"");
        assertThat(json).contains("2026").contains("10:15:30");
    }

    @Test
    void shouldSerializeInstantAsBeijingTime() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of(
                "createdAt", Instant.parse("2026-06-11T02:15:30Z")
        ), 4000, new String[0]);

        assertThat(json).contains("2026-06-11T10:15:30+08:00");
        assertThat(json).doesNotContain("2026-06-11T02:15:30Z");
    }

    @Test
    void shouldSanitizeCollectionsArraysAndPojoFieldsRecursively() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        String json = sanitizer.toSanitizedJson(Map.of(
                "items", List.of(new CredentialPayload("alice", "plain-password")),
                "array", new Object[]{Map.of("stdout", "敏感输出"), Map.of("safe", "保留")}
        ), 4000, new String[]{"apiKey"});

        assertThat(json).contains("\"username\":\"alice\"");
        assertThat(json).contains("\"password\":\"******\"");
        assertThat(json).contains("\"stdout\":\"******\"");
        assertThat(json).contains("\"safe\":\"保留\"");
        assertThat(json).doesNotContain("plain-password");
        assertThat(json).doesNotContain("敏感输出");
    }

    @Test
    void shouldReturnNullForNullInputAndTruncateNullSafely() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();

        assertThat(sanitizer.toSanitizedJson(null, 4000, new String[0])).isNull();
        assertThat(sanitizer.truncate(null, 10)).isNull();
    }

    @Test
    void shouldReturnUnserializableMarkerWhenJacksonFails() {
        OperationLogSanitizer sanitizer = new OperationLogSanitizer();
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);

        assertThat(sanitizer.toSanitizedJson(cyclic, 4000, new String[0]))
                .isEqualTo("\"<unserializable>\"");
    }

    private record CredentialPayload(String username, String password) {
    }
}

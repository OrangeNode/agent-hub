package com.agenthub.tool.mcp.client;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.mcp.entity.McpServer;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class HttpMcpClient implements McpClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25";
    private static final String CLIENT_NAME = "Agent Hub";
    private static final String CLIENT_VERSION = "0.0.1";

    private final HttpClient httpClient;

    public HttpMcpClient() {
        this(HttpClient.newHttpClient());
    }

    public HttpMcpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public McpInitializeResult initialize(McpServer server) {
        JsonNode root = sendJsonRpc(server, "initialize", Map.of(
                "protocolVersion", protocolVersion(server),
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", CLIENT_NAME, "version", CLIENT_VERSION)
        ));
        sendNotification(server, "notifications/initialized");
        JsonNode result = root.path("result");
        return new McpInitializeResult(
                text(result, "protocolVersion"),
                toMap(result.path("capabilities")),
                toMap(result.path("serverInfo"))
        );
    }

    @Override
    public List<McpToolDescriptor> listTools(McpServer server) {
        JsonNode root = sendJsonRpc(server, "tools/list", Map.of());
        List<McpToolDescriptor> tools = new ArrayList<>();
        for (JsonNode tool : root.path("result").path("tools")) {
            String name = text(tool, "name");
            tools.add(new McpToolDescriptor(
                    name,
                    firstText(tool, "title", "displayName", "name"),
                    text(tool, "description"),
                    toMap(tool.path("inputSchema"))
            ));
        }
        return tools;
    }

    @Override
    public McpToolCallResult callTool(McpServer server, String toolName, Map<String, Object> arguments) {
        JsonNode root = sendJsonRpc(server, "tools/call", Map.of(
                "name", toolName,
                "arguments", arguments == null ? Map.of() : arguments
        ));
        JsonNode result = root.path("result");
        return new McpToolCallResult(
                result.path("isError").asBoolean(false),
                contentText(result.path("content"))
        );
    }

    private JsonNode sendJsonRpc(McpServer server, String method, Map<String, Object> params) {
        Map<String, Object> body = new HashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        return readJsonRpcResponse(exchange(server, body));
    }

    private void sendNotification(McpServer server, String method) {
        exchange(server, Map.of(
                "jsonrpc", "2.0",
                "method", method
        ));
    }

    private HttpResponse<String> exchange(McpServer server, Map<String, Object> body) {
        try {
            HttpRequest request = requestBuilder(server)
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 请求失败: HTTP " + response.statusCode());
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 请求被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 请求失败");
        }
    }

    private HttpRequest.Builder requestBuilder(McpServer server) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.getEndpointUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds(server.getRequestTimeoutSeconds(), 60)))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("MCP-Protocol-Version", protocolVersion(server));
        applyAuthAndHeaders(builder, server);
        return builder;
    }

    private void applyAuthAndHeaders(HttpRequest.Builder builder, McpServer server) {
        Map<String, Object> extraHeaders = parseExtraHeaders(server.getExtraHeaders());
        String authType = normalize(server.getAuthType());
        if ("BEARER".equals(authType) && hasText(server.getAuthTokenCipher())) {
            builder.header("Authorization", "Bearer " + server.getAuthTokenCipher());
        } else if ("API_KEY_HEADER".equals(authType) && hasText(server.getAuthTokenCipher())) {
            String headerName = stringValue(extraHeaders.getOrDefault("apiKeyHeaderName", "X-API-Key"));
            if (hasText(headerName) && !isDangerousHeader(headerName)) {
                builder.header(headerName, server.getAuthTokenCipher());
            }
        }
        extraHeaders.forEach((name, value) -> {
            if (!hasText(name) || "apiKeyHeaderName".equals(name) || isDangerousHeader(name)) {
                return;
            }
            String headerValue = stringValue(value);
            if (hasText(headerValue)) {
                builder.header(name, headerValue);
            }
        });
    }

    private JsonNode readJsonRpcResponse(HttpResponse<String> response) {
        JsonNode root;
        if (contentType(response).contains("text/event-stream")) {
            root = readSseJsonRpcResponse(response.body());
        } else {
            root = readJson(response.body());
        }
        if (root.hasNonNull("error")) {
            String message = root.path("error").path("message").asText("MCP JSON-RPC 调用失败");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, message);
        }
        if (!root.has("result")) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 响应缺少 result");
        }
        return root;
    }

    private JsonNode readSseJsonRpcResponse(String body) {
        List<String> dataLines = new ArrayList<>();
        for (String rawLine : (body == null ? "" : body).split("\\R", -1)) {
            String line = rawLine == null ? "" : rawLine;
            if (line.isEmpty()) {
                JsonNode message = readSseMessage(dataLines);
                dataLines.clear();
                if (message != null) {
                    return message;
                }
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }
        JsonNode message = readSseMessage(dataLines);
        if (message != null) {
            return message;
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP SSE 响应缺少 JSON-RPC 结果");
    }

    private JsonNode readSseMessage(List<String> dataLines) {
        if (dataLines == null || dataLines.isEmpty()) {
            return null;
        }
        JsonNode root = readJson(String.join("\n", dataLines));
        if (root.has("result") || root.has("error")) {
            return root;
        }
        return null;
    }

    private JsonNode readJson(String body) {
        try {
            return OBJECT_MAPPER.readTree(body == null ? "" : body);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "MCP 响应解析失败");
        }
    }

    private String contentText(JsonNode content) {
        if (content == null || content.isMissingNode() || content.isNull()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (content.isArray()) {
            for (JsonNode item : content) {
                if ("text".equals(item.path("type").asText()) && item.has("text")) {
                    parts.add(item.path("text").asText(""));
                } else {
                    parts.add(toCompactJson(item));
                }
            }
        } else {
            parts.add(toCompactJson(content));
        }
        return String.join("\n", parts);
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        return OBJECT_MAPPER.convertValue(node, MAP_TYPE);
    }

    private Map<String, Object> parseExtraHeaders(String value) {
        if (!hasText(value)) {
            return Map.of();
        }
        JsonNode root = readJson(value);
        if (!root.isObject()) {
            return Map.of();
        }
        return toMap(root);
    }

    private String toCompactJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private String contentType(HttpResponse<String> response) {
        return response.headers()
                .firstValue("content-type")
                .orElse("")
                .toLowerCase(Locale.ROOT);
    }

    private String protocolVersion(McpServer server) {
        return hasText(server.getProtocolVersion()) ? server.getProtocolVersion() : DEFAULT_PROTOCOL_VERSION;
    }

    private int timeoutSeconds(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }

    private String text(JsonNode node, String fieldName) {
        return node == null ? null : node.path(fieldName).asText(null);
    }

    private String firstText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            String value = text(node, fieldName);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isDangerousHeader(String name) {
        String normalized = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return normalized.equals("host")
                || normalized.equals("content-length")
                || normalized.equals("content-type")
                || normalized.equals("accept")
                || normalized.equals("authorization")
                || normalized.equals("mcp-protocol-version");
    }
}

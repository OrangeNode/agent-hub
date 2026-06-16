package com.agenthub.sandbox.client;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionRequest;
import com.agenthub.sandbox.client.dto.CreateSandboxSessionResponse;
import com.agenthub.sandbox.client.dto.ExecSandboxRequest;
import com.agenthub.sandbox.client.dto.SandboxArtifactItem;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.client.dto.SkillZipSyncRequest;
import com.agenthub.sandbox.client.dto.SkillZipSyncResult;
import com.agenthub.sandbox.config.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpSandboxClient implements SandboxClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final SandboxProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public HttpSandboxClient(SandboxProperties properties) {
        this(properties, HttpClient.newHttpClient());
    }

    public HttpSandboxClient(SandboxProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public CreateSandboxSessionResponse createSession(CreateSandboxSessionRequest request) {
        try {
            HttpRequest httpRequest = requestBuilder("/internal/sandbox/sessions")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(request)))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "创建沙箱会话失败");
            return OBJECT_MAPPER.readValue(response.body(), CreateSandboxSessionResponse.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.VM_START_FAILED, "创建沙箱会话被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VM_START_FAILED, "创建沙箱会话失败");
        }
    }

    @Override
    public boolean sessionExists(String sandboxSessionId) {
        if (sandboxSessionId == null || sandboxSessionId.isBlank()) {
            return false;
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder("/internal/sandbox/sessions/" + encodePath(sandboxSessionId) + "/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            int statusCode = response.statusCode();
            if (statusCode == 404 || statusCode == 503) {
                return false;
            }
            requireSuccess(statusCode, "查询沙箱会话健康状态失败");
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱会话健康状态被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱会话健康状态失败");
        }
    }

    @Override
    public void streamExec(String sandboxSessionId, ExecSandboxRequest request, Consumer<SandboxEvent> eventConsumer) {
        try {
            HttpRequest httpRequest = requestBuilder("/internal/sandbox/sessions/" + encodePath(sandboxSessionId) + "/exec/stream")
                    .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(request)))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            requireSuccess(response.statusCode(), "沙箱命令执行失败");
            List<String> block = new ArrayList<>();
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeSseLine(line, block, eventConsumer));
            }
            flushSseBlock(block, eventConsumer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.COMMAND_FAILED, "沙箱命令执行被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.COMMAND_FAILED, "沙箱命令执行失败");
        }
    }

    @Override
    public List<SandboxFileItem> listFiles(String sandboxSessionId, String path, boolean recursive) {
        try {
            String apiPath = "/internal/sandbox/sessions/" + encodePath(sandboxSessionId)
                    + "/files?path=" + encodeQuery(path)
                    + "&recursive=" + recursive;
            HttpResponse<String> response = httpClient.send(requestBuilder(apiPath).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "查询沙箱文件失败");
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            List<SandboxFileItem> files = new ArrayList<>();
            for (JsonNode item : root.path("files")) {
                files.add(toSandboxFileItem(item));
            }
            return files;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱文件被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱文件失败");
        }
    }

    @Override
    public String readFile(String sandboxSessionId, String path, int maxBytes) {
        try {
            String apiPath = "/internal/sandbox/sessions/" + encodePath(sandboxSessionId)
                    + "/files/content?path=" + encodeQuery(path)
                    + "&maxBytes=" + maxBytes;
            HttpResponse<String> response = httpClient.send(requestBuilder(apiPath).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "读取沙箱文件失败");
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            if (root.path("truncated").asBoolean(false)) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "文件大小超过限制");
            }
            return root.path("content").asText("");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取沙箱文件被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取沙箱文件失败");
        }
    }

    @Override
    public List<SandboxArtifactItem> writeFile(String sandboxSessionId, String path, String content, boolean append) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(new WriteFileRequest(path, content, append));
            HttpRequest request = requestBuilder("/internal/sandbox/sessions/" + encodePath(sandboxSessionId) + "/files/content")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "写入沙箱文件失败");
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            List<SandboxArtifactItem> artifacts = new ArrayList<>();
            for (JsonNode item : root.path("artifacts")) {
                artifacts.add(toSandboxArtifactItem(item));
            }
            return artifacts;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写入沙箱文件被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写入沙箱文件失败");
        }
    }

    @Override
    public SkillZipSyncResult syncSkillZips(String sandboxSessionId, SkillZipSyncRequest request) {
        try {
            String body = OBJECT_MAPPER.writeValueAsString(request);
            HttpRequest httpRequest = requestBuilder("/internal/sandbox/sessions/"
                    + encodePath(sandboxSessionId) + "/skills/sync-zip")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response.statusCode(), "同步 Skill 包到沙箱失败");
            return OBJECT_MAPPER.readValue(response.body(), SkillZipSyncResult.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "同步 Skill 包到沙箱失败");
        }
    }

    public static List<SandboxEvent> parseSseBlocks(List<String> lines) {
        List<SandboxEvent> events = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                events.addAll(parseSseBlock(block));
                block.clear();
            } else {
                block.add(line);
            }
        }
        events.addAll(parseSseBlock(block));
        return events;
    }

    private static void consumeSseLine(String line, List<String> block, Consumer<SandboxEvent> eventConsumer) {
        if (line == null || line.isEmpty()) {
            flushSseBlock(block, eventConsumer);
            return;
        }
        block.add(line);
    }

    private static void flushSseBlock(List<String> block, Consumer<SandboxEvent> eventConsumer) {
        for (SandboxEvent event : parseSseBlock(block)) {
            eventConsumer.accept(event);
        }
        block.clear();
    }

    private static List<SandboxEvent> parseSseBlock(List<String> block) {
        if (block == null || block.isEmpty()) {
            return List.of();
        }
        String eventType = "message";
        List<String> dataLines = new ArrayList<>();
        for (String line : block) {
            if (line.startsWith("event:")) {
                eventType = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                String value = line.substring("data:".length());
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                dataLines.add(value);
            }
        }
        return List.of(toSandboxEvent(eventType, String.join("\n", dataLines)));
    }

    private static SandboxEvent toSandboxEvent(String eventType, String data) {
        try {
            JsonNode root = data == null || data.isBlank() ? OBJECT_MAPPER.createObjectNode() : OBJECT_MAPPER.readTree(data);
            SandboxArtifactItem artifact = null;
            if ("artifact_synced".equals(eventType)) {
                artifact = new SandboxArtifactItem(
                        longValue(root, "artifactId"),
                        fileName(root),
                        text(root, "relativePath"),
                        firstText(root, "storageBucket", "bucket"),
                        text(root, "storageKey"),
                        firstText(root, "contentType", "application/octet-stream"),
                        firstLong(root, "sizeBytes", "size"),
                        text(root, "sha256"),
                        intValue(root, "version")
                );
            }
            return new SandboxEvent(
                    eventType,
                    text(root, "executionId"),
                    firstText(root, "content", "message"),
                    intValue(root, "exitCode"),
                    longValue(root, "durationMs"),
                    text(root, "errorCode"),
                    text(root, "message"),
                    artifact,
                    root.path("timedOut").asBoolean(false)
            );
        } catch (Exception exception) {
            return new SandboxEvent(eventType, null, data, null, null, null, null, null, false);
        }
    }

    static SandboxFileItem toSandboxFileItemForTest(String json) {
        try {
            return toSandboxFileItem(OBJECT_MAPPER.readTree(json));
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    private static SandboxFileItem toSandboxFileItem(JsonNode item) {
        String path = firstText(item, "path", "relativePath");
        if (path != null && !path.startsWith("/workspace")) {
            path = "/workspace/" + path.replaceAll("^/+", "");
        }
        String name = firstText(item, "name");
        if (name == null || name.isBlank()) {
            name = fileNameFromPath(path);
        }
        return new SandboxFileItem(
                name,
                path,
                longValue(item, "size"),
                item.path("directory").asBoolean(item.path("isDir").asBoolean(false))
        );
    }

    private static SandboxArtifactItem toSandboxArtifactItem(JsonNode item) {
        String relativePath = text(item, "relativePath");
        String fileName = firstText(item, "fileName", "name");
        if (fileName == null || fileName.isBlank()) {
            fileName = fileNameFromPath(relativePath);
        }
        return new SandboxArtifactItem(
                longValue(item, "artifactId"),
                fileName,
                relativePath,
                firstText(item, "storageBucket", "bucket"),
                text(item, "storageKey"),
                firstText(item, "contentType", "application/octet-stream"),
                firstLong(item, "sizeBytes", "size"),
                text(item, "sha256"),
                intValue(item, "version")
        );
    }

    private HttpRequest.Builder requestBuilder(String pathAndQuery) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json, text/event-stream");
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank()) {
            builder.header("Authorization", "Bearer " + properties.getInternalToken());
        }
        return builder;
    }

    private void requireSuccess(int statusCode, String message) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, message);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱服务地址不能为空");
        }
        return value.replaceAll("/+$", "");
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String fileNameFromPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static Long firstLong(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longValue(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Integer intValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    private static String fileName(JsonNode node) {
        String fileName = text(node, "fileName");
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        String relativePath = text(node, "relativePath");
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        int index = relativePath.replace('\\', '/').lastIndexOf('/');
        return index >= 0 ? relativePath.substring(index + 1) : relativePath;
    }

    private record WriteFileRequest(String path, String content, @JsonProperty("appendMode") boolean append) {
    }
}

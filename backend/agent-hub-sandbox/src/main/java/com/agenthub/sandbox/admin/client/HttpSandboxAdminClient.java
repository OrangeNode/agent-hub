package com.agenthub.sandbox.admin.client;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.admin.dto.SandboxControlResult;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeSession;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.config.SandboxProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HttpSandboxAdminClient implements SandboxAdminClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final SandboxProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public HttpSandboxAdminClient(SandboxProperties properties) {
        this(properties, HttpClient.newHttpClient());
    }

    public HttpSandboxAdminClient(SandboxProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public SandboxRuntimeStatus status() {
        return get("/internal/admin/sandbox/status", SandboxRuntimeStatus.class, "查询沙箱运行态失败");
    }

    @Override
    public List<SandboxRuntimeSession> sessions() {
        SandboxRuntimeSession[] items = get("/internal/admin/sandbox/sessions", SandboxRuntimeSession[].class,
                "查询沙箱运行会话失败");
        return Arrays.asList(items);
    }

    @Override
    public SandboxResourceSnapshot resources() {
        return get("/internal/admin/sandbox/resources", SandboxResourceSnapshot.class, "查询沙箱资源失败");
    }

    @Override
    public SandboxControlResult reapIdle() {
        return post("/internal/admin/sandbox/reap-idle", "{}", "回收空闲沙箱失败");
    }

    @Override
    public SandboxControlResult closeSession(String sessionId) {
        return post("/internal/admin/sandbox/sessions/" + encodePath(sessionId) + "/close", "{}",
                "关闭沙箱会话失败");
    }

    @Override
    public SandboxControlResult killVm(String vmId) {
        return post("/internal/admin/sandbox/vms/" + encodePath(vmId) + "/kill", "{}", "终止沙箱 VM 失败");
    }

    @Override
    public SandboxControlResult cleanupOrphans(boolean dryRun) {
        return post("/internal/admin/sandbox/resources/cleanup-orphans", "{\"dryRun\":" + dryRun + "}",
                "清理沙箱残留资源失败");
    }

    @Override
    public SandboxControlResult clearRunDir() {
        return post("/internal/admin/sandbox/run-dir/clear", "{}", "清空沙箱运行目录失败");
    }

    @Override
    public SandboxControlResult rebuildWarmPool() {
        return post("/internal/admin/sandbox/warm-pool/rebuild", "{}", "重建沙箱预热池失败");
    }

    @Override
    public String tailLogs(int lines) {
        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder("/internal/admin/sandbox/logs/tail?lines=" + lines).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(response.statusCode(), "查询沙箱日志失败");
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            return root.path("content").asText("");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱日志被中断");
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "查询沙箱日志失败");
        }
    }

    private <T> T get(String path, Class<T> type, String errorMessage) {
        try {
            HttpResponse<String> response = httpClient.send(requestBuilder(path).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(response.statusCode(), errorMessage);
            return OBJECT_MAPPER.readValue(response.body(), type);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage);
        }
    }

    private SandboxControlResult post(String path, String body, String errorMessage) {
        try {
            HttpRequest request = requestBuilder(path)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            requireSuccess(response.statusCode(), errorMessage);
            return OBJECT_MAPPER.readValue(response.body(), SandboxControlResult.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage);
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, errorMessage);
        }
    }

    private HttpRequest.Builder requestBuilder(String pathAndQuery) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getBaseUrl()) + pathAndQuery))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json");
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

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "沙箱服务地址不能为空");
        }
        return value.replaceAll("/+$", "");
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

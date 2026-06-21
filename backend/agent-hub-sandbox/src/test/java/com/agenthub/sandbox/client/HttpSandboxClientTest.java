package com.agenthub.sandbox.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.client.dto.SkillZipSyncRequest;
import com.agenthub.sandbox.client.dto.SkillZipSyncResult;
import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.config.SandboxProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpSandboxClientTest {

    @Test
    void shouldParseSseBlocks() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: stdout_delta",
                "data: {\"content\":\"hello\"}",
                "",
                "event: completed",
                "data: {\"executionId\":\"exec_1\",\"exitCode\":0}",
                ""
        ));

        assertThat(events).hasSize(2);
        assertThat(events.get(0).type()).isEqualTo("stdout_delta");
        assertThat(events.get(0).content()).isEqualTo("hello");
        assertThat(events.get(1).type()).isEqualTo("completed");
        assertThat(events.get(1).executionId()).isEqualTo("exec_1");
        assertThat(events.get(1).exitCode()).isZero();
    }

    @Test
    void shouldJoinMultilineSseData() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: stderr_delta",
                "data: line1",
                "data: line2",
                ""
        ));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().content()).isEqualTo("line1\nline2");
    }

    @Test
    void shouldParseTimedOutEvent() {
        List<SandboxEvent> events = HttpSandboxClient.parseSseBlocks(List.of(
                "event: completed",
                "data: {\"executionId\":\"exec_1\",\"exitCode\":-1,\"timedOut\":true}",
                ""
        ));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().timedOut()).isTrue();
    }

    @Test
    void shouldMapGuestFileFields() {
        SandboxFileItem item = HttpSandboxClient.toSandboxFileItemForTest("""
                {"relativePath":"input/data.csv","size":10,"isDir":false}
                """);

        assertThat(item.name()).isEqualTo("data.csv");
        assertThat(item.path()).isEqualTo("/workspace/input/data.csv");
        assertThat(item.size()).isEqualTo(10L);
        assertThat(item.directory()).isFalse();
    }

    @Test
    void shouldCheckWhetherRuntimeSessionExists() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        properties.setBaseUrl("http://sandbox-service");
        properties.setInternalToken("token");
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> okResponse = mock(HttpResponse.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> missingResponse = mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(missingResponse.statusCode()).thenReturn(404);
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(okResponse)
                .thenReturn(missingResponse);
        HttpSandboxClient client = new HttpSandboxClient(properties, httpClient);

        boolean exists = client.sessionExists("sbx_2");
        boolean missing = client.sessionExists("sbx_missing");

        assertThat(exists).isTrue();
        assertThat(missing).isFalse();
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).send(
                requestCaptor.capture(),
                org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()
        );
        assertThat(requestCaptor.getAllValues().get(0).uri().toString())
                .isEqualTo("http://sandbox-service/internal/sandbox/sessions/sbx_2/health");
        assertThat(requestCaptor.getAllValues().get(1).uri().toString())
                .isEqualTo("http://sandbox-service/internal/sandbox/sessions/sbx_missing/health");
        assertThat(requestCaptor.getValue().headers().firstValue("Authorization"))
                .contains("Bearer token");
    }

    @Test
    void shouldTreatUnhealthyRuntimeSessionAsMissing() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        properties.setBaseUrl("http://sandbox-service");
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        HttpSandboxClient client = new HttpSandboxClient(properties, httpClient);

        assertThat(client.sessionExists("sbx_unhealthy")).isFalse();
    }

    @Test
    void shouldSyncSkillZipsWithBearerTokenAndBody() throws Exception {
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedAuthorization = new AtomicReference<>();
        AtomicReference<String> capturedContentType = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedMethod.set(exchange.getRequestMethod());
            capturedPath.set(exchange.getRequestURI().getRawPath());
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"ok":true,"skills":[{"skillId":6,"skillKey":"docx","targetDir":"/workspace/skills/docx","archivePath":"/tmp/docx.zip","fileCount":3,"totalBytes":128}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setInternalToken("token");
            HttpSandboxClient client = new HttpSandboxClient(properties);
            String sha256 = "a".repeat(64);
            SkillZipSyncRequest request = new SkillZipSyncRequest(List.of(
                    new SkillZipSyncRequest.Skill(
                            6L,
                            "docx",
                            "docx",
                            "https://minio.example.com/docx.zip",
                            sha256,
                            "/workspace/skills/docx"
                    )
            ));

            SkillZipSyncResult result = client.syncSkillZips("sbx 1/docx", request);

            assertThat(result.ok()).isTrue();
            assertThat(result.skills()).hasSize(1);
            assertThat(result.skills().getFirst().archivePath()).isEqualTo("/tmp/docx.zip");
            assertThat(capturedMethod.get()).isEqualTo("POST");
            assertThat(capturedPath.get()).isEqualTo("/internal/sandbox/sessions/sbx%201%2Fdocx/skills/sync-zip");
            assertThat(capturedAuthorization.get()).isEqualTo("Bearer token");
            assertThat(capturedContentType.get()).isEqualTo("application/json; charset=utf-8");
            assertThat(capturedBody.get()).isEqualTo("{\"skills\":[{\"skillId\":6,\"skillKey\":\"docx\",\"name\":\"docx\",\"zipDownloadUrl\":\"https://minio.example.com/docx.zip\",\"zipSha256\":\""
                    + sha256 + "\",\"targetDir\":\"/workspace/skills/docx\"}]}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldWriteBinaryFileAsBase64Content() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"ok\":true,\"artifacts\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            HttpSandboxClient client = new HttpSandboxClient(properties);
            byte[] content = new byte[]{0x50, 0x4b, 0x03, 0x04, (byte) 0xff, 0x00, (byte) 0x80};

            client.writeFileBytes("sbx_1", "/workspace/input/slide.png", content, false);

            assertThat(capturedBody.get()).contains("\"path\":\"/workspace/input/slide.png\"");
            assertThat(capturedBody.get()).contains("\"contentBase64\":\"" + Base64.getEncoder().encodeToString(content) + "\"");
            assertThat(capturedBody.get()).contains("\"appendMode\":false");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectSkillZipSyncWhenResponseOkIsFalse() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        properties.setBaseUrl("http://127.0.0.1:18080");
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"ok\":false,\"skills\":[]}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        HttpSandboxClient client = new HttpSandboxClient(properties, httpClient);

        assertThatThrownBy(() -> client.syncSkillZips("sbx_1", new SkillZipSyncRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同步 Skill 包到沙箱失败");
    }

    @Test
    void shouldIncludeSandboxErrorBodyWhenSkillZipSyncHttpFails() throws Exception {
        SandboxProperties properties = new SandboxProperties();
        properties.setBaseUrl("http://127.0.0.1:18080");
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(400);
        when(response.body()).thenReturn("{\"error\":\"docx: mkdir /workspace/skills/.tmp/docx/scripts/office/schemas: not a directory\"}");
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        HttpSandboxClient client = new HttpSandboxClient(properties, httpClient);

        assertThatThrownBy(() -> client.syncSkillZips("sbx_1", new SkillZipSyncRequest(List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a directory");
    }
}

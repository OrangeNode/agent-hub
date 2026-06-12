package com.agenthub.sandbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.admin.client.HttpSandboxAdminClient;
import com.agenthub.sandbox.admin.dto.SandboxResourceSnapshot;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.config.SandboxProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpSandboxAdminClientTest {

    @Test
    void shouldSendBearerTokenAndParseStatus() throws Exception {
        CapturingServer server = CapturingServer.start(200, """
                {
                  "serviceHealthy": true,
                  "activeSessions": 2,
                  "resourceSummary": {
                    "firecrackerProcesses": 3,
                    "tapDevices": 4,
                    "runDirBytes": 1024,
                    "orphanResources": 1
                  },
                  "sessions": [],
                  "warmPool": {"targetSize":5,"size":1,"starting":0,"vms":[]},
                  "resources": {"runDirs":[],"firecrackerProcesses":[],"taps":[],"orphans":[]}
                }
                """);
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl(server.baseUrl());
            properties.setInternalToken("secret-token");
            HttpSandboxAdminClient client = new HttpSandboxAdminClient(properties);

            SandboxRuntimeStatus status = client.status();

            assertThat(server.authorization()).isEqualTo("Bearer secret-token");
            assertThat(status.serviceHealthy()).isTrue();
            assertThat(status.activeSessions()).isEqualTo(2);
            assertThat(status.resourceSummary().firecrackerProcesses()).isEqualTo(3);
            assertThat(status.resourceSummary().orphanResources()).isEqualTo(1);
        } finally {
            server.stop();
        }
    }

    @Test
    void shouldParseRealResourceSnapshotWithNullOrphans() throws Exception {
        CapturingServer server = CapturingServer.start(200, """
                {
                  "scannedAt": "2026-06-11T20:27:21.342421327+08:00",
                  "runDir": "/home/orange/agent-hub/sandbox-service/run",
                  "runDirBytes": 8589951141,
                  "runDirs": [{
                    "vmId": "fc-1781172928516687522",
                    "path": "/home/orange/agent-hub/sandbox-service/run/fc-1781172928516687522",
                    "sizeBytes": 8589951141,
                    "createdAt": "2026-06-11T18:20:13.045057871+08:00",
                    "hasSocket": true,
                    "bound": true,
                    "resourceStatus": "ACTIVE"
                  }],
                  "firecrackerProcesses": [{
                    "pid": 3396951,
                    "args": "/usr/local/bin/firecracker --api-sock /home/orange/agent-hub/sandbox-service/run/fc-1781172928516687522/firecracker.socket",
                    "apiSocket": "/home/orange/agent-hub/sandbox-service/run/fc-1781172928516687522/firecracker.socket",
                    "vmId": "fc-1781172928516687522",
                    "runDir": "/home/orange/agent-hub/sandbox-service/run/fc-1781172928516687522",
                    "bound": true,
                    "resourceStatus": "ACTIVE"
                  }],
                  "taps": [{
                    "name": "tap-ah000001",
                    "vmId": "fc-1781172928516687522",
                    "bound": true,
                    "resourceStatus": "ACTIVE"
                  }],
                  "orphans": null
                }
                """);
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl(server.baseUrl());
            properties.setInternalToken("secret-token");
            HttpSandboxAdminClient client = new HttpSandboxAdminClient(properties);

            SandboxResourceSnapshot resources = client.resources();

            assertThat(resources.runDirBytes()).isEqualTo(8589951141L);
            assertThat(resources.runDirs()).hasSize(1);
            assertThat(resources.firecrackerProcesses()).hasSize(1);
            assertThat(resources.taps()).hasSize(1);
            assertThat(resources.orphans()).isEmpty();
        } finally {
            server.stop();
        }
    }

    @Test
    void shouldThrowBusinessExceptionWhenStatusReturnsNon2xx() throws Exception {
        CapturingServer server = CapturingServer.start(503, "{\"error\":\"down\"}");
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl(server.baseUrl());
            HttpSandboxAdminClient client = new HttpSandboxAdminClient(properties);

            assertThatThrownBy(client::status)
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("查询沙箱运行态失败");
        } finally {
            server.stop();
        }
    }

    @Test
    void shouldForwardDryRunToDangerousControlEndpoints() throws Exception {
        CapturingServer server = CapturingServer.start(200, "{\"success\":true,\"affected\":[],\"errors\":[]}");
        try {
            SandboxProperties properties = new SandboxProperties();
            properties.setBaseUrl(server.baseUrl());
            HttpSandboxAdminClient client = new HttpSandboxAdminClient(properties);

            client.killVm("fc-1", true);
            assertThat(server.path()).isEqualTo("/internal/admin/sandbox/vms/fc-1/kill");
            assertThat(server.body()).contains("\"dryRun\":true");

            client.clearRunDir(true);
            assertThat(server.path()).isEqualTo("/internal/admin/sandbox/run-dir/clear");
            assertThat(server.body()).contains("\"dryRun\":true");

            client.rebuildWarmPool(true);
            assertThat(server.path()).isEqualTo("/internal/admin/sandbox/warm-pool/rebuild");
            assertThat(server.body()).contains("\"dryRun\":true");
        } finally {
            server.stop();
        }
    }

    private static final class CapturingServer {
        private final HttpServer server;
        private volatile String authorization;
        private final AtomicReference<String> path = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();

        private CapturingServer(HttpServer server) {
            this.server = server;
        }

        private static CapturingServer start(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CapturingServer capturingServer = new CapturingServer(server);
            server.createContext("/", exchange -> {
                capturingServer.authorization = exchange.getRequestHeaders().getFirst("Authorization");
                capturingServer.path.set(exchange.getRequestURI().getPath());
                capturingServer.body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(statusCode, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return capturingServer;
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private String authorization() {
            return authorization;
        }

        private String path() {
            return path.get();
        }

        private String body() {
            return body.get();
        }

        private void stop() {
            server.stop(0);
        }
    }
}

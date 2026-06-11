package com.agenthub.sandbox.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.sandbox.admin.client.HttpSandboxAdminClient;
import com.agenthub.sandbox.admin.dto.SandboxRuntimeStatus;
import com.agenthub.sandbox.config.SandboxProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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

    private static final class CapturingServer {
        private final HttpServer server;
        private volatile String authorization;

        private CapturingServer(HttpServer server) {
            this.server = server;
        }

        private static CapturingServer start(int statusCode, String responseBody) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CapturingServer capturingServer = new CapturingServer(server);
            server.createContext("/", exchange -> {
                capturingServer.authorization = exchange.getRequestHeaders().getFirst("Authorization");
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

        private void stop() {
            server.stop(0);
        }
    }
}

package com.agenthub.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.ai.provider.dto.AiProviderConnectionTestResult;
import com.agenthub.ai.provider.entity.AiProvider;
import com.agenthub.ai.provider.service.impl.HttpAiProviderConnectivityTester;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AiProviderConnectivityTesterTest {

    @Test
    void shouldProbeOpenAiCompatibleModelsEndpointWithBearerToken() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProvider provider = provider("OPENAI_COMPATIBLE", "http://127.0.0.1:" + server.getAddress().getPort());
            HttpAiProviderConnectivityTester tester = new HttpAiProviderConnectivityTester(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build());

            AiProviderConnectionTestResult result = tester.test(provider);

            assertThat(result.success()).isTrue();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(path.get()).isEqualTo("/v1/models");
            assertThat(authorization.get()).isEqualTo("Bearer sk-test");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseApiKeyHeaderWhenAuthTypeIsApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            if (!"sk-test".equals(apiKey.get())) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProvider provider = provider("OPENAI_COMPATIBLE", "http://127.0.0.1:" + server.getAddress().getPort());
            provider.setAuthType("API_KEY");
            HttpAiProviderConnectivityTester tester = new HttpAiProviderConnectivityTester(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build());

            AiProviderConnectionTestResult result = tester.test(provider);

            assertThat(result.success()).isTrue();
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(authorization.get()).isNull();
            assertThat(apiKey.get()).isEqualTo("sk-test");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyExtraHeadersToModelsProbe() throws Exception {
        AtomicReference<String> customHeader = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            customHeader.set(exchange.getRequestHeaders().getFirst("X-Workspace"));
            byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            AiProvider provider = provider("OPENAI_COMPATIBLE", "http://127.0.0.1:" + server.getAddress().getPort());
            provider.setExtraHeaders("{\"X-Workspace\":\"agent-hub\"}");
            HttpAiProviderConnectivityTester tester = new HttpAiProviderConnectivityTester(HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build());

            AiProviderConnectionTestResult result = tester.test(provider);

            assertThat(result.success()).isTrue();
            assertThat(customHeader.get()).isEqualTo("agent-hub");
        } finally {
            server.stop(0);
        }
    }

    private AiProvider provider(String protocolType, String baseUrl) {
        AiProvider provider = new AiProvider();
        provider.setProtocolType(protocolType);
        provider.setBaseUrl(baseUrl);
        provider.setApiKeyCipher("sk-test");
        provider.setAuthType("BEARER");
        provider.setApiVersion("v1");
        provider.setExtraHeaders("{}");
        return provider;
    }
}

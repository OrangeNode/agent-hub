package com.agenthub.tool.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.common.exception.BusinessException;
import com.agenthub.tool.mcp.entity.McpServer;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HttpMcpClientTest {

    @Test
    void shouldSendInitializeWithMcpHeaders() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers("application/json"));
        when(response.body()).thenReturn("""
                {"jsonrpc":"2.0","id":"1","result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"demo","version":"1.0.0"}}}
                """);
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        HttpMcpClient client = new HttpMcpClient(httpClient);

        McpInitializeResult result = client.initialize(server());

        assertThat(result.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(result.capabilities()).containsKey("tools");
        assertThat(result.serverInfo()).containsEntry("name", "demo");
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient, org.mockito.Mockito.times(2)).send(
                requestCaptor.capture(),
                org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()
        );
        HttpRequest initializeRequest = requestCaptor.getAllValues().getFirst();
        assertThat(initializeRequest.method()).isEqualTo("POST");
        assertThat(initializeRequest.uri().toString()).isEqualTo("https://mcp.example.com/mcp");
        assertThat(initializeRequest.headers().firstValue("Accept")).contains("application/json, text/event-stream");
        assertThat(initializeRequest.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(initializeRequest.headers().firstValue("MCP-Protocol-Version")).contains("2025-11-25");
        assertThat(initializeRequest.headers().firstValue("Authorization")).contains("Bearer token-cipher");
    }

    @Test
    void shouldListToolsFromJsonResponse() throws Exception {
        HttpMcpClient client = new HttpMcpClient(httpClientWithJson("""
                {"jsonrpc":"2.0","id":"1","result":{"tools":[{"name":"search","description":"搜索仓库","inputSchema":{"type":"object","properties":{"query":{"type":"string"}}}}]}}
                """));

        List<McpToolDescriptor> tools = client.listTools(server());

        assertThat(tools).hasSize(1);
        McpToolDescriptor descriptor = tools.getFirst();
        assertThat(descriptor.name()).isEqualTo("search");
        assertThat(descriptor.displayName()).isEqualTo("search");
        assertThat(descriptor.description()).isEqualTo("搜索仓库");
        assertThat(descriptor.inputSchema()).containsEntry("type", "object");
    }

    @Test
    void shouldCallToolFromJsonResponse() throws Exception {
        HttpMcpClient client = new HttpMcpClient(httpClientWithJson("""
                {"jsonrpc":"2.0","id":"call_1","result":{"content":[{"type":"text","text":"ok"}],"isError":false}}
                """));

        McpToolCallResult result = client.callTool(server(), "search", Map.of("query", "agent hub"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("ok");
    }

    @Test
    void shouldReadFinalJsonRpcResultFromSseResponse() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers("text/event-stream"));
        when(response.body()).thenReturn("""
                event: message
                data: {"jsonrpc":"2.0","id":"call_1","result":{"content":[{"type":"text","text":"ok"}]}}

                """);
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        HttpMcpClient client = new HttpMcpClient(httpClient);

        McpToolCallResult result = client.callTool(server(), "search", Map.of("query", "agent hub"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("ok");
    }

    @Test
    void shouldThrowReadableExceptionForJsonRpcError() throws Exception {
        HttpMcpClient client = new HttpMcpClient(httpClientWithJson("""
                {"jsonrpc":"2.0","id":"1","error":{"code":-32601,"message":"method not found"}}
                """));

        assertThatThrownBy(() -> client.listTools(server()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("method not found");
    }

    private HttpClient httpClientWithJson(String body) throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(headers("application/json"));
        when(response.body()).thenReturn(body);
        when(httpClient.send(any(HttpRequest.class), org.mockito.Mockito.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);
        return httpClient;
    }

    private HttpHeaders headers(String contentType) {
        return HttpHeaders.of(Map.of("content-type", List.of(contentType)), (name, value) -> true);
    }

    private McpServer server() {
        McpServer server = new McpServer();
        server.setServerKey("github");
        server.setName("GitHub");
        server.setEndpointUrl("https://mcp.example.com/mcp");
        server.setProtocolVersion("2025-11-25");
        server.setAuthType("BEARER");
        server.setAuthTokenCipher("token-cipher");
        server.setExtraHeaders("{}");
        server.setConnectTimeoutSeconds(10);
        server.setRequestTimeoutSeconds(60);
        return server;
    }
}

package com.agenthub.sandbox.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenthub.sandbox.client.dto.SandboxEvent;
import com.agenthub.sandbox.client.dto.SandboxFileItem;
import com.agenthub.sandbox.config.SandboxProperties;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
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
}

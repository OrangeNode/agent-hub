package com.agenthub.storage.object;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.storage.config.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PresignEndpointResolverTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldUseExplicitPublicEndpointFirst() {
        StorageProperties properties = storageProperties();
        properties.setPublicEndpoint("https://storage.example.com");
        bindRequest("agent.example.com:5174");

        String endpoint = new PresignEndpointResolver(properties).resolve();

        assertThat(endpoint).isEqualTo("https://storage.example.com");
    }

    @Test
    void shouldDeriveEndpointFromForwardedHostWhenPublicEndpointIsBlank() {
        StorageProperties properties = storageProperties();
        bindRequest("agent.example.com:5174");

        String endpoint = new PresignEndpointResolver(properties).resolve();

        assertThat(endpoint).isEqualTo("http://agent.example.com:9000");
    }

    @Test
    void shouldFallbackToInternalEndpointOutsideHttpRequest() {
        StorageProperties properties = storageProperties();

        String endpoint = new PresignEndpointResolver(properties).resolve();

        assertThat(endpoint).isEqualTo("http://127.0.0.1:9000");
    }

    private StorageProperties storageProperties() {
        StorageProperties properties = new StorageProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        return properties;
    }

    private void bindRequest(String forwardedHost) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/attachments/upload-url");
        request.addHeader("X-Forwarded-Host", forwardedHost);
        request.addHeader("Host", "127.0.0.1:8080");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes((HttpServletRequest) request));
    }
}

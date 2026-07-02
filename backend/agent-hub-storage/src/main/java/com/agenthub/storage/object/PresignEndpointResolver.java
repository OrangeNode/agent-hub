package com.agenthub.storage.object;

import com.agenthub.storage.config.StorageProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class PresignEndpointResolver {

    private static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";

    private final StorageProperties properties;

    PresignEndpointResolver(StorageProperties properties) {
        this.properties = properties;
    }

    String resolve() {
        String explicitPublicEndpoint = trimToNull(properties.getPublicEndpoint());
        if (explicitPublicEndpoint != null) {
            return explicitPublicEndpoint;
        }

        String defaultEndpoint = defaultEndpoint();
        String requestHost = currentRequestHost();
        if (requestHost == null) {
            return defaultEndpoint;
        }
        return deriveEndpoint(defaultEndpoint, requestHost);
    }

    String defaultEndpoint() {
        String explicitPublicEndpoint = trimToNull(properties.getPublicEndpoint());
        if (explicitPublicEndpoint != null) {
            return explicitPublicEndpoint;
        }
        return trimToNull(properties.getEndpoint());
    }

    private String currentRequestHost() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletRequestAttributes)) {
            return null;
        }

        HttpServletRequest request = servletRequestAttributes.getRequest();
        String forwardedHost = firstHeaderValue(request.getHeader(FORWARDED_HOST_HEADER));
        if (forwardedHost != null) {
            return forwardedHost;
        }

        String host = trimToNull(request.getHeader("Host"));
        if (host != null) {
            return host;
        }
        return trimToNull(request.getServerName());
    }

    private String deriveEndpoint(String defaultEndpoint, String requestHost) {
        if (defaultEndpoint == null) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(defaultEndpoint);
        } catch (IllegalArgumentException exception) {
            return defaultEndpoint;
        }

        String scheme = trimToNull(uri.getScheme());
        String host = hostWithoutPort(requestHost);
        if (scheme == null || host == null) {
            return defaultEndpoint;
        }

        StringBuilder endpoint = new StringBuilder(scheme).append("://").append(host);
        int port = uri.getPort();
        if (port > 0) {
            endpoint.append(':').append(port);
        }
        return endpoint.toString();
    }

    private String hostWithoutPort(String value) {
        String host = trimToNull(value);
        if (host == null) {
            return null;
        }
        if (host.startsWith("[")) {
            int closeBracketIndex = host.indexOf(']');
            return closeBracketIndex > 0 ? host.substring(0, closeBracketIndex + 1) : host;
        }

        int firstColonIndex = host.indexOf(':');
        int lastColonIndex = host.lastIndexOf(':');
        if (firstColonIndex > 0 && firstColonIndex == lastColonIndex) {
            return host.substring(0, firstColonIndex);
        }
        return host;
    }

    private String firstHeaderValue(String value) {
        String header = trimToNull(value);
        if (header == null) {
            return null;
        }

        int commaIndex = header.indexOf(',');
        if (commaIndex < 0) {
            return header;
        }
        return trimToNull(header.substring(0, commaIndex));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

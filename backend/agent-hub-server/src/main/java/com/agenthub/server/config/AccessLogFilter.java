package com.agenthub.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class AccessLogFilter extends OncePerRequestFilter {

    private final boolean enabled;

    public AccessLogFilter(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 按配置决定是否记录访问日志，并确保异常场景也能输出耗时。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled) {
            filterChain.doFilter(request, response);
            return;
        }

        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMs = System.currentTimeMillis() - startedAt;
            log.info(
                    "access method={} path={} status={} costMs={} ip={}",
                    request.getMethod(),
                    buildPath(request),
                    response.getStatus(),
                    costMs,
                    request.getRemoteAddr()
            );
        }
    }

    /**
     * 拼接请求路径和查询参数，便于定位具体访问。
     */
    private String buildPath(HttpServletRequest request) {
        String queryString = request.getQueryString();
        if (queryString == null || queryString.isBlank()) {
            return request.getRequestURI();
        }
        return request.getRequestURI() + "?" + queryString;
    }
}

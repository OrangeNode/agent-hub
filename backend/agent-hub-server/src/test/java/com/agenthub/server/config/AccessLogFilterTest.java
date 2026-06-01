package com.agenthub.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTest {

    @Test
    void shouldSkipLoggingWhenAccessLogDisabled() throws ServletException, IOException {
        AccessLogFilter filter = new AccessLogFilter(false);
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/users");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> response.setStatus(200);

        filter.doFilter(request, response, chain);

        assertThat(appender.list).isEmpty();
        logger.detachAppender(appender);
    }

    @Test
    void shouldWriteAccessLogWhenAccessLogEnabled() throws ServletException, IOException {
        AccessLogFilter filter = new AccessLogFilter(true);
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/users");
        request.setQueryString("username=admin");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> response.setStatus(200);

        filter.doFilter(request, response, chain);

        assertThat(appender.list).hasSize(1);
        String message = appender.list.getFirst().getFormattedMessage();
        assertThat(message).contains("GET");
        assertThat(message).contains("/api/system/users");
        assertThat(message).contains("status=200");
        assertThat(message).contains("ip=127.0.0.1");
        logger.detachAppender(appender);
    }
}

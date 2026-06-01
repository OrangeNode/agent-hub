package com.agenthub.server.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AgentHubLoggingProperties.class)
public class LoggingConfig {

    @Bean
    AccessLogFilter accessLogFilter(AgentHubLoggingProperties properties) {
        return new AccessLogFilter(properties.isAccessLogEnabled());
    }
}

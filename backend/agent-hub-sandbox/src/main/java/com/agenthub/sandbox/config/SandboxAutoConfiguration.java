package com.agenthub.sandbox.config;

import com.agenthub.sandbox.policy.CommandPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxAutoConfiguration {

    @Bean
    public CommandPolicy sandboxCommandPolicy(SandboxProperties properties) {
        return new CommandPolicy(properties.getCommandMaxLength(), properties.getExecTimeoutSeconds());
    }
}

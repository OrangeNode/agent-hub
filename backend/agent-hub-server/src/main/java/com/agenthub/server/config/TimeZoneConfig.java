package com.agenthub.server.config;

import com.agenthub.common.time.AgentHubTimeZone;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TimeZoneConfig {

    public TimeZoneConfig() {
        AgentHubTimeZone.applyDefault();
    }
}

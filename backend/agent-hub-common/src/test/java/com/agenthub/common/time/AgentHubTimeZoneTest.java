package com.agenthub.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class AgentHubTimeZoneTest {

    @Test
    void shouldApplyBeijingAsDefaultTimeZone() {
        TimeZone original = TimeZone.getDefault();
        String originalProperty = System.getProperty("user.timezone");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            System.setProperty("user.timezone", "UTC");

            AgentHubTimeZone.applyDefault();

            assertThat(ZoneId.systemDefault()).isEqualTo(AgentHubTimeZone.BEIJING_ZONE);
            assertThat(System.getProperty("user.timezone")).isEqualTo(AgentHubTimeZone.BEIJING_ZONE_ID);
        } finally {
            TimeZone.setDefault(original);
            if (originalProperty == null) {
                System.clearProperty("user.timezone");
            } else {
                System.setProperty("user.timezone", originalProperty);
            }
        }
    }
}

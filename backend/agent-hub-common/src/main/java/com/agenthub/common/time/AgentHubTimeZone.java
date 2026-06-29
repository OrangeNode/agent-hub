package com.agenthub.common.time;

import java.time.ZoneId;
import java.util.TimeZone;

public final class AgentHubTimeZone {

    public static final String BEIJING_ZONE_ID = "Asia/Shanghai";
    public static final ZoneId BEIJING_ZONE = ZoneId.of(BEIJING_ZONE_ID);

    private AgentHubTimeZone() {
    }

    public static void applyDefault() {
        System.setProperty("user.timezone", BEIJING_ZONE_ID);
        TimeZone.setDefault(TimeZone.getTimeZone(BEIJING_ZONE));
    }
}

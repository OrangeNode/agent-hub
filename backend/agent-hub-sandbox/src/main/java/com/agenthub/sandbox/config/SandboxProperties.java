package com.agenthub.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent-hub.sandbox")
public class SandboxProperties {

    private String baseUrl = "http://127.0.0.1:8090";
    private String internalToken = "";
    private int commandMaxLength = 8192;
    private int execTimeoutSeconds = 300;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getCommandMaxLength() {
        return commandMaxLength;
    }

    public void setCommandMaxLength(int commandMaxLength) {
        this.commandMaxLength = commandMaxLength;
    }

    public int getExecTimeoutSeconds() {
        return execTimeoutSeconds;
    }

    public void setExecTimeoutSeconds(int execTimeoutSeconds) {
        this.execTimeoutSeconds = execTimeoutSeconds;
    }
}

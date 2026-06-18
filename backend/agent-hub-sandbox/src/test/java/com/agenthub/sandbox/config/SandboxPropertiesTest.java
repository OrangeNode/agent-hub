package com.agenthub.sandbox.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SandboxPropertiesTest {

    @Test
    void shouldDefaultExecTimeoutToSandboxServiceMaximum() {
        SandboxProperties properties = new SandboxProperties();

        assertThat(properties.getExecTimeoutSeconds()).isEqualTo(300);
    }
}

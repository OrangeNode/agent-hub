package com.agenthub.security.jwt;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

    @Test
    void shouldRejectBlankSecret() {
        assertThatThrownBy(() -> new JwtProperties("  ", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_HUB_JWT_SECRET");
    }

    @Test
    void shouldRejectShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("too-short", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    @Test
    void shouldRejectKnownPublicSecrets() {
        assertThatThrownBy(() -> new JwtProperties("agent-hub-demo-secret-agent-hub-demo-secret", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开的示例值");
        assertThatThrownBy(() -> new JwtProperties("agent-hub-prod-secret-change-me-please-32chars", Duration.ofHours(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公开的示例值");
    }

    @Test
    void shouldRejectNonPositiveTtl() {
        assertThatThrownBy(() -> new JwtProperties("agent-hub-test-secret-agent-hub-test-secret", Duration.ZERO))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldAcceptStrongSecret() {
        assertThatCode(() -> new JwtProperties("agent-hub-test-secret-agent-hub-test-secret", Duration.ofHours(1)))
                .doesNotThrowAnyException();
    }
}

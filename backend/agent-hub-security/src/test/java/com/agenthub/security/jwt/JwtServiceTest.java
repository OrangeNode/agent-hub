package com.agenthub.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.security.principal.LoginUser;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    @Test
    void shouldCreateAndParseToken() {
        JwtProperties properties = new JwtProperties(
                "agent-hub-test-secret-agent-hub-test-secret",
                Duration.ofHours(2)
        );
        JwtService jwtService = new JwtService(properties);
        LoginUser loginUser = new LoginUser(
                1L,
                "admin",
                List.of("ADMIN"),
                List.of("system:user:list", "agent:manage")
        );

        String token = jwtService.createToken(loginUser);
        LoginUser parsed = jwtService.parseToken(token);

        assertThat(parsed.userId()).isEqualTo(1L);
        assertThat(parsed.username()).isEqualTo("admin");
        assertThat(parsed.roles()).containsExactly("ADMIN");
        assertThat(parsed.permissions()).containsExactly("system:user:list", "agent:manage");
    }
}

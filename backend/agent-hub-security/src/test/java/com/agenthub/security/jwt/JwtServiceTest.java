package com.agenthub.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenthub.security.principal.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import javax.crypto.SecretKey;
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
                List.of("ADMIN")
        );

        String token = jwtService.createToken(loginUser);
        LoginUser parsed = jwtService.parseToken(token);
        SecretKey secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertThat(parsed.userId()).isEqualTo(1L);
        assertThat(parsed.username()).isEqualTo("admin");
        assertThat(parsed.roles()).containsExactly("ADMIN");
        assertThat(claims.get("permissions")).isNull();
    }
}

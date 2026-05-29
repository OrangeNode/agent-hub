package com.agenthub.security.jwt;

import java.time.Duration;

public record JwtProperties(String secret, Duration ttl) {
}

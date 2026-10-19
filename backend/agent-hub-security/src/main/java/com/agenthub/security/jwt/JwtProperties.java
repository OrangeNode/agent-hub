package com.agenthub.security.jwt;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

public record JwtProperties(String secret, Duration ttl) {

    /**
     * HS256 至少需要 256 位密钥，按 UTF-8 字节长度校验。
     */
    static final int MIN_SECRET_BYTES = 32;

    /**
     * 曾经写进代码或仓库样例的公开密钥，生产环境误用等同于没有签名保护。
     */
    private static final Set<String> KNOWN_PUBLIC_SECRETS = Set.of(
            "agent-hub-demo-secret-agent-hub-demo-secret",
            "agent-hub-prod-secret-change-me-please-32chars"
    );

    public JwtProperties {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("未配置 JWT 签名密钥，请设置环境变量 AGENT_HUB_JWT_SECRET");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT 签名密钥长度不足，至少需要 " + MIN_SECRET_BYTES + " 字节");
        }
        if (KNOWN_PUBLIC_SECRETS.contains(secret)) {
            throw new IllegalStateException("JWT 签名密钥使用了公开的示例值，请更换为随机生成的密钥");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalStateException("JWT 有效期必须大于 0");
        }
    }
}

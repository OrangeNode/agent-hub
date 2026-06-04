package com.agenthub.security.jwt;

import com.agenthub.common.api.ErrorCode;
import com.agenthub.common.exception.BusinessException;
import com.agenthub.security.principal.LoginUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.SecretKey;

public class JwtService {

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.secretKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 根据登录用户信息签发 JWT。
     */
    public String createToken(LoginUser loginUser) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(loginUser.username())
                .claim("userId", loginUser.userId())
                .claim("username", loginUser.username())
                .claim("roles", loginUser.roles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.ttl())))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验 JWT，失败时转换为统一未认证业务异常。
     */
    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new LoginUser(
                    claims.get("userId", Long.class),
                    claims.get("username", String.class),
                    castStringList(claims.get("roles"))
            );
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /**
     * 将 JWT claims 中的角色集合安全转换为字符串列表。
     */
    private List<String> castStringList(Object rawValue) {
        if (rawValue instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }

        return List.of();
    }
}

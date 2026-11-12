package com.bizbitenow.printservice.security;

import com.bizbitenow.printservice.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private final JwtProperties properties;
    private SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialize() {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(UUID deviceId, String sellerId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(deviceId.toString())
                .claim("sellerId", sellerId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TTL)))
                .signWith(signingKey)
                .compact();
    }

    public DevicePrincipal parseAccessToken(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        return new DevicePrincipal(UUID.fromString(claims.getSubject()), claims.get("sellerId", String.class));
    }

    public Duration accessTokenTtl() {
        return ACCESS_TTL;
    }
}

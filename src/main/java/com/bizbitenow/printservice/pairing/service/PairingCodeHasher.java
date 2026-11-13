package com.bizbitenow.printservice.pairing.service;

import com.bizbitenow.printservice.config.JwtProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

@Component
public final class PairingCodeHasher {
    private static final String KEY_DERIVATION_CONTEXT = "bizbitenow:pairing-code-hash-key:v1";
    private final SecretKeySpec key;

    public PairingCodeHasher(JwtProperties properties) {
        String secret = properties.jwtSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        }
        byte[] derivedKey = hmac(secret.getBytes(StandardCharsets.UTF_8),
                KEY_DERIVATION_CONTEXT.getBytes(StandardCharsets.UTF_8));
        this.key = new SecretKeySpec(derivedKey, "HmacSHA256");
    }

    public String hashCode(String code) {
        return HexFormat.of().formatHex(hmac(key.getEncoded(), code.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }
}

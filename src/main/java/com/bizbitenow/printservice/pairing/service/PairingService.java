package com.bizbitenow.printservice.pairing.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.device.dto.PairDeviceRequest;
import com.bizbitenow.printservice.device.dto.TokenResponse;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.pairing.dto.PairingCodeResponse;
import com.bizbitenow.printservice.pairing.entity.PairingCode;
import com.bizbitenow.printservice.pairing.repository.PairingCodeRepository;
import com.bizbitenow.printservice.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service
public class PairingService {
    private static final char[] CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final Duration PAIRING_TTL = Duration.ofMinutes(10);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private final SecureRandom random = new SecureRandom();
    private final PairingCodeRepository pairingCodes;
    private final AppDeviceRepository devices;
    private final JwtService jwtService;
    private final PairingCodeHasher pairingCodeHasher;

    public PairingService(PairingCodeRepository pairingCodes, AppDeviceRepository devices, JwtService jwtService,
                          PairingCodeHasher pairingCodeHasher) {
        this.pairingCodes = pairingCodes;
        this.devices = devices;
        this.jwtService = jwtService;
        this.pairingCodeHasher = pairingCodeHasher;
    }

    @Transactional
    public PairingCodeResponse createCode(String sellerId) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String code = randomCode();
            Instant expiresAt = Instant.now().plus(PAIRING_TTL);
            int inserted = pairingCodes.insertIfAbsent(UUID.randomUUID(), sellerId,
                    pairingCodeHasher.hashCode(code), expiresAt);
            if (inserted == 1) return new PairingCodeResponse(code, expiresAt);
        }
        throw new IllegalStateException("Unable to allocate a unique pairing code");
    }

    @Transactional
    public TokenResponse pair(PairDeviceRequest request) {
        Instant now = Instant.now();
        PairingCode pairingCode = pairingCodes.findUsableForUpdate(pairingCodeHasher.hashCode(request.code()), now)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_PAIRING_CODE", "Pairing code is invalid, expired, or already used"));
        pairingCode.setUsedAt(now);
        AppDevice device = new AppDevice();
        device.setSellerId(pairingCode.getSellerId());
        device.setDeviceName(request.deviceName().trim());
        device.setAndroidVersion(request.androidVersion());
        device.setAppVersion(request.appVersion());
        device.setFcmToken(blankToNull(request.fcmToken()));
        device.setLastSeenAt(now);
        device.setActive(true);
        String refreshToken = newRefreshToken();
        device.setRefreshTokenHash(hash(refreshToken));
        device.setRefreshTokenExpiresAt(now.plus(REFRESH_TTL));
        devices.save(device);
        return tokenResponse(device, refreshToken, now);
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        AppDevice device = devices.findRefreshTokenForUpdate(hash(rawRefreshToken))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED,
                        "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));
        if (device.getRefreshTokenExpiresAt() == null || !device.getRefreshTokenExpiresAt().isAfter(Instant.now())) {
            device.setRefreshTokenHash(null);
            device.setRefreshTokenExpiresAt(null);
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                    "Refresh token is invalid or expired");
        }
        String rotated = newRefreshToken();
        device.setRefreshTokenHash(hash(rotated));
        Instant now = Instant.now();
        device.setRefreshTokenExpiresAt(now.plus(REFRESH_TTL));
        return tokenResponse(device, rotated, now);
    }

    public static String hash(String token) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private TokenResponse tokenResponse(AppDevice device, String refreshToken, Instant now) {
        return new TokenResponse(jwtService.issueAccessToken(device.getId(), device.getSellerId()), refreshToken,
                device.getId(), device.getSellerId(), now.plus(jwtService.accessTokenTtl()));
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(8);
        for (int i = 0; i < 8; i++) builder.append(CODE_CHARS[random.nextInt(CODE_CHARS.length)]);
        return builder.toString();
    }

    private String newRefreshToken() {
        byte[] value = new byte[48];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

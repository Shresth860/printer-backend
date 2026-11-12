package com.bizbitenow.printservice.device.dto;

import java.time.Instant;
import java.util.UUID;

public record TokenResponse(String accessToken, String refreshToken, UUID deviceId, String sellerId,
                            Instant accessTokenExpiresAt) {
}

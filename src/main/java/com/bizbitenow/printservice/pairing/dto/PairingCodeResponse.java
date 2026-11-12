package com.bizbitenow.printservice.pairing.dto;

import java.time.Instant;

public record PairingCodeResponse(String code, Instant expiresAt) {
}

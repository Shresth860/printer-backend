package com.bizbitenow.printservice.security;

import java.util.UUID;

public record DevicePrincipal(UUID deviceId, String sellerId) {
}

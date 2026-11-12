package com.bizbitenow.printservice.device.dto;

import java.time.Instant;
import java.util.UUID;

public record DeviceResponse(UUID id, String sellerId, String deviceName, String androidVersion,
                             String appVersion, Instant lastSeenAt, boolean active, boolean online) {
}

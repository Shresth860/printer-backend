package com.bizbitenow.printservice.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PairDeviceRequest(@NotBlank @Size(min = 8, max = 8) String code,
                                @NotBlank @Size(max = 160) String deviceName,
                                @NotBlank @Size(max = 80) String androidVersion,
                                @NotBlank @Size(max = 80) String appVersion,
                                @Size(max = 4096) String fcmToken) {
}

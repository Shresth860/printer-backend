package com.bizbitenow.printservice.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PrinterStatusRequest(@NotBlank @Pattern(regexp = "CONNECTED|DISCONNECTED") String status) {
}

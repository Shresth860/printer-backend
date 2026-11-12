package com.bizbitenow.printservice.device.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record HeartbeatRequest(@NotBlank @Size(max = 80) String appVersion,
                               @NotNull @Valid @Size(max = 100) List<PrinterHeartbeat> printers) {
    public record PrinterHeartbeat(@NotBlank @Size(max = 160) String name,
                                   @NotBlank @Size(max = 80) String status) {
    }
}

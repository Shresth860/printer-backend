package com.bizbitenow.printservice.printer.dto;

import com.bizbitenow.printservice.common.ConnectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PrinterUpsertRequest(@NotNull @Size(max = 20) List<PrinterInput> printers) {
    public record PrinterInput(@NotBlank @Size(max = 160) String name,
                               @NotNull ConnectionType connectionType,
                               @Size(max = 512) String address,
                               int paperWidthMm,
                               boolean defaultPrinter,
                               @Size(max = 80) String status) {
    }
}

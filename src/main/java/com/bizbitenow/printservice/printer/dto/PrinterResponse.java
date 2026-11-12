package com.bizbitenow.printservice.printer.dto;

import com.bizbitenow.printservice.common.ConnectionType;

import java.util.UUID;

public record PrinterResponse(UUID id, String name, ConnectionType connectionType, String address,
                             int paperWidthMm, boolean defaultPrinter, String lastStatus) {
}

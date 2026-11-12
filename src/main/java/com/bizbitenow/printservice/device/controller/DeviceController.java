package com.bizbitenow.printservice.device.controller;

import com.bizbitenow.printservice.device.dto.DeviceResponse;
import com.bizbitenow.printservice.device.dto.FcmTokenRequest;
import com.bizbitenow.printservice.device.dto.HeartbeatRequest;
import com.bizbitenow.printservice.device.service.DeviceService;
import com.bizbitenow.printservice.printer.dto.PrinterResponse;
import com.bizbitenow.printservice.printer.dto.PrinterUpsertRequest;
import com.bizbitenow.printservice.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeviceController {
    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/devices/me/heartbeat")
    @SecurityRequirement(name = "deviceBearer")
    public void heartbeat(@AuthenticationPrincipal DevicePrincipal principal,
                          @Valid @RequestBody HeartbeatRequest request) {
        deviceService.heartbeat(principal, request);
    }

    @PutMapping("/devices/me/fcm-token")
    @SecurityRequirement(name = "deviceBearer")
    public void updateFcmToken(@AuthenticationPrincipal DevicePrincipal principal,
                               @Valid @RequestBody FcmTokenRequest request) {
        deviceService.updateFcmToken(principal, request.fcmToken());
    }

    @PutMapping("/devices/me/printers")
    @SecurityRequirement(name = "deviceBearer")
    public List<PrinterResponse> upsertPrinters(@AuthenticationPrincipal DevicePrincipal principal,
                                                @Valid @RequestBody PrinterUpsertRequest request) {
        return deviceService.upsertPrinters(principal, request);
    }

    @org.springframework.web.bind.annotation.GetMapping("/sellers/{sellerId}/devices")
    @SecurityRequirement(name = "serviceKey")
    public List<DeviceResponse> sellerDevices(@PathVariable String sellerId) {
        return deviceService.sellerDevices(sellerId);
    }

    @DeleteMapping("/devices/{id}")
    @SecurityRequirement(name = "serviceKey")
    public void revoke(@PathVariable UUID id) {
        deviceService.revoke(id);
    }
}

package com.bizbitenow.printservice.device.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.device.dto.DeviceResponse;
import com.bizbitenow.printservice.device.dto.HeartbeatRequest;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.printer.dto.PrinterResponse;
import com.bizbitenow.printservice.printer.dto.PrinterUpsertRequest;
import com.bizbitenow.printservice.printer.entity.Printer;
import com.bizbitenow.printservice.printer.repository.PrinterRepository;
import com.bizbitenow.printservice.security.DevicePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeviceService {
    private final AppDeviceRepository devices;
    private final PrinterRepository printers;

    public DeviceService(AppDeviceRepository devices, PrinterRepository printers) {
        this.devices = devices;
        this.printers = printers;
    }

    @Transactional
    public void heartbeat(DevicePrincipal principal, HeartbeatRequest request) {
        AppDevice device = requireActive(principal.deviceId());
        Instant now = Instant.now();
        device.setLastSeenAt(now);
        device.setAppVersion(request.appVersion());
        for (HeartbeatRequest.PrinterHeartbeat heartbeat : request.printers()) {
            printers.findByDeviceIdAndName(device.getId(), heartbeat.name()).ifPresent(printer ->
                    printer.setLastStatus(heartbeat.status()));
        }
    }

    @Transactional
    public void updateFcmToken(DevicePrincipal principal, String token) {
        requireActive(principal.deviceId()).setFcmToken(token);
    }

    @Transactional
    public List<PrinterResponse> upsertPrinters(DevicePrincipal principal, PrinterUpsertRequest request) {
        AppDevice device = requireActive(principal.deviceId());
        long defaultCount = request.printers().stream().filter(PrinterUpsertRequest.PrinterInput::defaultPrinter).count();
        if (defaultCount > 1) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRINTERS",
                    "At most one printer can be the default");
        }
        printers.findByDeviceId(device.getId()).forEach(printer -> printer.setDefaultPrinter(false));
        for (PrinterUpsertRequest.PrinterInput input : request.printers()) {
            if (input.paperWidthMm() != 58 && input.paperWidthMm() != 80) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PRINTERS",
                        "paperWidthMm must be 58 or 80");
            }
            Printer printer = printers.findByDeviceIdAndName(device.getId(), input.name()).orElseGet(Printer::new);
            printer.setDevice(device);
            printer.setSellerId(device.getSellerId());
            printer.setName(input.name());
            printer.setConnectionType(input.connectionType());
            printer.setAddress(input.address());
            printer.setPaperWidthMm(input.paperWidthMm());
            printer.setDefaultPrinter(input.defaultPrinter());
            printer.setLastStatus(input.status());
            printers.save(printer);
        }
        return printers.findByDeviceIdOrderByName(device.getId()).stream().map(DeviceService::printerResponse).toList();
    }

    @Transactional
    public void deactivateFcmToken(UUID deviceId) {
        devices.findById(deviceId).ifPresent(device -> device.setFcmToken(null));
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> sellerDevices(String sellerId) {
        Instant onlineThreshold = Instant.now().minusSeconds(120);
        return devices.findBySellerIdOrderByCreatedAtDesc(sellerId).stream()
                .map(device -> deviceResponse(device, onlineThreshold)).toList();
    }

    @Transactional
    public void revoke(UUID id) {
        AppDevice device = devices.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "Device not found"));
        revoke(device);
    }

    @Transactional
    public void revokeSelf(DevicePrincipal principal) {
        revoke(requireActive(principal.deviceId()));
    }

    private void revoke(AppDevice device) {
        device.setActive(false);
        device.setRefreshTokenHash(null);
        device.setRefreshTokenExpiresAt(null);
        device.setFcmToken(null);
    }

    public AppDevice requireActive(UUID id) {
        return devices.findById(id).filter(AppDevice::isActive).orElseThrow(() ->
                new BusinessException(HttpStatus.UNAUTHORIZED, "DEVICE_REVOKED", "Device is not active"));
    }

    private static DeviceResponse deviceResponse(AppDevice device, Instant onlineThreshold) {
        boolean online = device.isActive() && device.getLastSeenAt() != null
                && device.getLastSeenAt().isAfter(onlineThreshold);
        return new DeviceResponse(device.getId(), device.getSellerId(), device.getDeviceName(),
                device.getAndroidVersion(), device.getAppVersion(), device.getLastSeenAt(), device.isActive(), online);
    }

    private static PrinterResponse printerResponse(Printer printer) {
        return new PrinterResponse(printer.getId(), printer.getName(), printer.getConnectionType(), printer.getAddress(),
                printer.getPaperWidthMm(), printer.isDefaultPrinter(), printer.getLastStatus());
    }
}

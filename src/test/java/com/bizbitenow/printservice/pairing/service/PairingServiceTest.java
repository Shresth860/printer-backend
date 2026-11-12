package com.bizbitenow.printservice.pairing.service;

import com.bizbitenow.printservice.device.dto.PairDeviceRequest;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.pairing.entity.PairingCode;
import com.bizbitenow.printservice.pairing.repository.PairingCodeRepository;
import com.bizbitenow.printservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PairingServiceTest {
    private PairingCodeRepository pairingCodes;
    private AppDeviceRepository devices;
    private JwtService jwtService;
    private PairingService service;

    @BeforeEach
    void setUp() {
        pairingCodes = mock(PairingCodeRepository.class);
        devices = mock(AppDeviceRepository.class);
        jwtService = mock(JwtService.class);
        when(jwtService.issueAccessToken(any(), any())).thenReturn("access");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(devices.save(any())).thenAnswer(invocation -> {
            AppDevice device = invocation.getArgument(0);
            device.setId(UUID.randomUUID());
            return device;
        });
        service = new PairingService(pairingCodes, devices, jwtService);
    }

    @Test
    void pairingCodeCanBeUsedOnlyOnce() {
        PairingCode code = new PairingCode();
        code.setSellerId("seller-1");
        when(pairingCodes.findUsableForUpdate(eq("ABCD2345"), any()))
                .thenReturn(Optional.of(code), Optional.empty());

        assertEquals("seller-1", service.pair(request()).sellerId());
        assertThrows(RuntimeException.class, () -> service.pair(request()));
        verify(devices, times(1)).save(any(AppDevice.class));
    }

    @Test
    void expiredOrUnknownCodeIsRejected() {
        when(pairingCodes.findUsableForUpdate(eq("ABCD2345"), any())).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.pair(request()));
        verify(devices, never()).save(any());
    }

    @Test
    void refreshRotatesTheHashedTokenAndExpiry() {
        String oldToken = "existing-refresh-token";
        AppDevice device = new AppDevice();
        device.setId(UUID.randomUUID());
        device.setSellerId("seller-1");
        device.setRefreshTokenHash(PairingService.hash(oldToken));
        device.setRefreshTokenExpiresAt(Instant.now().plusSeconds(120));
        when(devices.findRefreshTokenForUpdate(PairingService.hash(oldToken))).thenReturn(Optional.of(device));

        var result = service.refresh(oldToken);

        assertNotEquals(oldToken, result.refreshToken());
        assertNotEquals(PairingService.hash(oldToken), device.getRefreshTokenHash());
        assertTrue(device.getRefreshTokenExpiresAt().isAfter(Instant.now().plus(Duration.ofDays(29))));
    }

    private PairDeviceRequest request() {
        return new PairDeviceRequest("ABCD2345", "Counter", "14", "1.2.0", "token");
    }
}

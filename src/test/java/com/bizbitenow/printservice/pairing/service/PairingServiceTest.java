package com.bizbitenow.printservice.pairing.service;

import com.bizbitenow.printservice.device.dto.PairDeviceRequest;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.config.JwtProperties;
import com.bizbitenow.printservice.pairing.entity.PairingCode;
import com.bizbitenow.printservice.pairing.repository.PairingCodeRepository;
import com.bizbitenow.printservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PairingServiceTest {
    private static final String RAW_CODE = "ABCD2345";
    private PairingCodeRepository pairingCodes;
    private AppDeviceRepository devices;
    private JwtService jwtService;
    private PairingCodeHasher pairingCodeHasher;
    private PairingService service;

    @BeforeEach
    void setUp() {
        pairingCodes = mock(PairingCodeRepository.class);
        devices = mock(AppDeviceRepository.class);
        jwtService = mock(JwtService.class);
        pairingCodeHasher = new PairingCodeHasher(new JwtProperties("unit-test-secret-material-over-32-bytes-long", ""));
        when(pairingCodes.insertIfAbsent(any(), any(), any(), any())).thenReturn(1);
        when(jwtService.issueAccessToken(any(), any())).thenReturn("access");
        when(jwtService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(devices.save(any())).thenAnswer(invocation -> {
            AppDevice device = invocation.getArgument(0);
            device.setId(UUID.randomUUID());
            return device;
        });
        service = new PairingService(pairingCodes, devices, jwtService, pairingCodeHasher);
    }

    @Test
    void pairingReplayReturnsSameDeviceWithoutCreatingDuplicate() {
        PairingCode code = new PairingCode();
        code.setSellerId("seller-1");
        code.setExpiresAt(Instant.now().plusSeconds(120));
        when(pairingCodes.findByCodeHashForUpdate(pairingCodeHasher.hashCode(RAW_CODE))).thenReturn(Optional.of(code));

        var first = service.pair(request(RAW_CODE));
        var pairedDevice = org.mockito.ArgumentCaptor.forClass(AppDevice.class);
        verify(devices).save(pairedDevice.capture());
        when(devices.findActiveForUpdate(first.deviceId())).thenReturn(Optional.of(pairedDevice.getValue()));
        var replay = service.pair(request(RAW_CODE));
        assertEquals("seller-1", first.sellerId());
        assertEquals(first.deviceId(), replay.deviceId());
        assertNotEquals(first.refreshToken(), replay.refreshToken());
        assertEquals(first.deviceId(), code.getPairedDeviceId());
        verify(devices, times(1)).save(any(AppDevice.class));
        verify(devices).findActiveForUpdate(first.deviceId());
    }

    @Test
    void expiredOrUnknownCodeIsRejected() {
        when(pairingCodes.findByCodeHashForUpdate(pairingCodeHasher.hashCode(RAW_CODE)))
                .thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.pair(request(RAW_CODE)));
        verify(devices, never()).save(any());
    }

    @Test
    void creationStoresOnlyAKeyedHashAndReturnsTheWireCode() {
        var response = service.createCode("seller-1");
        var codeHash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(pairingCodes).insertIfAbsent(any(), eq("seller-1"), codeHash.capture(), any());

        assertEquals(8, response.code().length());
        assertEquals(pairingCodeHasher.hashCode(response.code()), codeHash.getValue());
        assertNotEquals(response.code(), codeHash.getValue());
        assertTrue(response.expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(9))));
        assertTrue(response.expiresAt().isBefore(Instant.now().plus(Duration.ofMinutes(11))));
    }

    @Test
    void generationRetriesDatabaseUniquenessCollisions() {
        AtomicInteger insertions = new AtomicInteger();
        when(pairingCodes.insertIfAbsent(any(), any(), any(), any()))
                .thenAnswer(invocation -> insertions.incrementAndGet() == 1 ? 0 : 1);

        var response = service.createCode("seller-1");

        assertEquals(2, insertions.get());
        verify(pairingCodes, times(2)).insertIfAbsent(any(), eq("seller-1"), any(), any());
        assertEquals(64, pairingCodeHasher.hashCode(response.code()).length());
    }

    @Test
    void pairingCodeHashesAreKeyedAndDeterministic() {
        PairingCodeHasher otherHasher = new PairingCodeHasher(
                new JwtProperties("a-different-unit-test-secret-over-32-bytes", ""));

        assertEquals(pairingCodeHasher.hashCode(RAW_CODE), pairingCodeHasher.hashCode(RAW_CODE));
        assertNotEquals(pairingCodeHasher.hashCode(RAW_CODE), otherHasher.hashCode(RAW_CODE));
        assertNotEquals(RAW_CODE, pairingCodeHasher.hashCode(RAW_CODE));
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

    private PairDeviceRequest request(String code) {
        return new PairDeviceRequest(code, "Counter", "14", "1.2.0", "token");
    }
}

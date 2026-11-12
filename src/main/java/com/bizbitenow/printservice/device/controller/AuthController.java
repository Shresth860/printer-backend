package com.bizbitenow.printservice.device.controller;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.device.dto.PairDeviceRequest;
import com.bizbitenow.printservice.device.dto.RefreshTokenRequest;
import com.bizbitenow.printservice.device.dto.TokenResponse;
import com.bizbitenow.printservice.pairing.service.PairingRateLimiter;
import com.bizbitenow.printservice.pairing.service.PairingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final PairingService pairingService;
    private final PairingRateLimiter rateLimiter;

    public AuthController(PairingService pairingService, PairingRateLimiter rateLimiter) {
        this.pairingService = pairingService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/pair")
    public TokenResponse pair(@Valid @RequestBody PairDeviceRequest request, HttpServletRequest servletRequest) {
        String ip = servletRequest.getRemoteAddr();
        if (!rateLimiter.allow(ip)) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many pairing attempts; try again later");
        }
        return pairingService.pair(request);
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return pairingService.refresh(request.refreshToken());
    }
}

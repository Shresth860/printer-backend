package com.bizbitenow.printservice.pairing.controller;

import com.bizbitenow.printservice.pairing.dto.CreatePairingCodeRequest;
import com.bizbitenow.printservice.pairing.dto.PairingCodeResponse;
import com.bizbitenow.printservice.pairing.service.PairingService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pairing-codes")
public class PairingCodeController {
    private final PairingService pairingService;

    public PairingCodeController(PairingService pairingService) {
        this.pairingService = pairingService;
    }

    @PostMapping
    @SecurityRequirement(name = "serviceKey")
    public PairingCodeResponse create(@Valid @RequestBody CreatePairingCodeRequest request) {
        return pairingService.createCode(request.sellerId());
    }
}

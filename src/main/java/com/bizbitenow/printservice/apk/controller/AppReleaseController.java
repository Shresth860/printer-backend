package com.bizbitenow.printservice.apk.controller;

import com.bizbitenow.printservice.apk.dto.CreateReleaseRequest;
import com.bizbitenow.printservice.apk.dto.ReleaseResponse;
import com.bizbitenow.printservice.apk.service.AppReleaseService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AppReleaseController {
    private final AppReleaseService releaseService;

    public AppReleaseController(AppReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping("/releases")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "serviceKey")
    public ReleaseResponse create(@Valid @RequestBody CreateReleaseRequest request) {
        return releaseService.create(request);
    }

    @GetMapping("/app/latest-release")
    public ReleaseResponse latest() {
        return releaseService.latest();
    }
}

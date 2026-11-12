package com.bizbitenow.printservice.apk.service;

import com.bizbitenow.printservice.apk.dto.CreateReleaseRequest;
import com.bizbitenow.printservice.apk.dto.ReleaseResponse;
import com.bizbitenow.printservice.apk.entity.AppRelease;
import com.bizbitenow.printservice.apk.repository.AppReleaseRepository;
import com.bizbitenow.printservice.common.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppReleaseService {
    private final AppReleaseRepository releases;

    public AppReleaseService(AppReleaseRepository releases) {
        this.releases = releases;
    }

    @Transactional
    public ReleaseResponse create(CreateReleaseRequest request) {
        AppRelease release = new AppRelease();
        release.setVersionCode(request.versionCode());
        release.setVersionName(request.versionName());
        release.setApkUrl(request.apkUrl());
        release.setSha256(request.sha256().toLowerCase());
        release.setMandatory(request.mandatory());
        release.setReleaseNotes(request.releaseNotes());
        try {
            return toResponse(releases.saveAndFlush(release));
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "VERSION_ALREADY_EXISTS",
                    "A release with this version code already exists");
        }
    }

    @Transactional(readOnly = true)
    public ReleaseResponse latest() {
        return releases.findFirstByOrderByVersionCodeDesc().map(AppReleaseService::toResponse)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND",
                        "No application release is available"));
    }

    private static ReleaseResponse toResponse(AppRelease release) {
        return new ReleaseResponse(release.getVersionCode(), release.getVersionName(), release.getApkUrl(),
                release.getSha256(), release.isMandatory(), release.getReleaseNotes(), release.getCreatedAt());
    }
}

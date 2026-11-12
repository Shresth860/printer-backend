package com.bizbitenow.printservice.apk.dto;

import java.time.Instant;

public record ReleaseResponse(int versionCode, String versionName, String apkUrl, String sha256,
                              boolean mandatory, String releaseNotes, Instant createdAt) {
}

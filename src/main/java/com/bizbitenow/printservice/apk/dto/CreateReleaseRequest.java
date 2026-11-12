package com.bizbitenow.printservice.apk.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateReleaseRequest(@Min(1) int versionCode,
                                   @NotBlank @Size(max = 80) String versionName,
                                   @NotBlank @Size(max = 2048) String apkUrl,
                                   @NotBlank @Pattern(regexp = "(?i)[a-f0-9]{64}") String sha256,
                                   boolean mandatory,
                                   @Size(max = 4000) String releaseNotes) {
}

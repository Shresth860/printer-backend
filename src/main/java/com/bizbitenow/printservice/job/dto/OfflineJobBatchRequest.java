package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OfflineJobBatchRequest(@NotNull @Size(min = 1, max = 100) List<@Valid Result> results) {
    public record Result(@NotNull UUID clientId, @NotNull UUID jobId, @NotNull JobStatus status,
                         @NotNull Instant clientTimestamp, @Size(max = 120) String errorCode,
                         @Size(max = 1000) String errorMessage, Boolean connected,
                         Long bytesWritten, Boolean flushOk) {
    }
}

package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobStatus;

import java.util.List;
import java.util.UUID;

public record OfflineJobBatchResponse(List<Item> results) {
    public record Item(UUID clientId, UUID jobId, JobStatus lifecycleStatus, boolean applied, boolean duplicate) {
    }
}

package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.common.JobType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record PrintJobResponse(UUID id, String sellerId, UUID deviceId, String orderId, JobType jobType,
                               JsonNode payload, JobStatus status, int retryCount, int maxRetries,
                               String errorCode, String errorMessage, boolean openCashDrawer, boolean cutPaper,
                               int copies, Instant createdAt, Instant dispatchedAt, Instant completedAt) {
}

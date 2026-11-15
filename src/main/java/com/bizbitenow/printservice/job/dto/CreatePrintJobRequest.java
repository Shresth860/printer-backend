package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreatePrintJobRequest(@NotBlank @Size(max = 160) String sellerId,
                                    @NotBlank @Size(max = 160) String orderId,
                                    @NotNull JobType jobType,
                                    @NotNull JsonNode payload,
                                    boolean openCashDrawer,
                                    boolean cutPaper,
                                    @Min(1) @Max(10) int copies,
                                    @NotBlank @Size(max = 160) String idempotencyKey,
                                    UUID printerId) {
    public CreatePrintJobRequest(String sellerId, String orderId, JobType jobType, JsonNode payload,
                                 boolean openCashDrawer, boolean cutPaper, int copies, String idempotencyKey) {
        this(sellerId, orderId, jobType, payload, openCashDrawer, cutPaper, copies, idempotencyKey, null);
    }
}

package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateJobStatusRequest(@NotNull JobStatus status,
                                    @Size(max = 120) String errorCode,
                                    @Size(max = 1000) String errorMessage) {
}

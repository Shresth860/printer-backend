package com.bizbitenow.printservice.job.dto;

import com.bizbitenow.printservice.common.JobStatus;
import jakarta.validation.constraints.Size;

public record UpdateJobStatusRequest(JobStatus status,
                                    @Size(max = 120) String errorCode,
                                    @Size(max = 1000) String errorMessage) {
}

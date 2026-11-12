package com.bizbitenow.printservice.job.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.common.ReceiptPayloadValidator;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.device.service.DeviceService;
import com.bizbitenow.printservice.device.service.FcmPushService;
import com.bizbitenow.printservice.job.dto.CreatePrintJobRequest;
import com.bizbitenow.printservice.job.dto.PrintJobResponse;
import com.bizbitenow.printservice.job.dto.UpdateJobStatusRequest;
import com.bizbitenow.printservice.job.entity.PrintJob;
import com.bizbitenow.printservice.job.repository.PrintJobRepository;
import com.bizbitenow.printservice.security.DevicePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class PrintJobService {
    private final PrintJobRepository jobs;
    private final AppDeviceRepository devices;
    private final DeviceService deviceService;
    private final ReceiptPayloadValidator payloadValidator;
    private final PrintLogService logService;
    private final FcmPushService fcm;
    private final JdbcTemplate jdbc;

    public PrintJobService(PrintJobRepository jobs, AppDeviceRepository devices, DeviceService deviceService,
                           ReceiptPayloadValidator payloadValidator, PrintLogService logService,
                           FcmPushService fcm, JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.devices = devices;
        this.deviceService = deviceService;
        this.payloadValidator = payloadValidator;
        this.logService = logService;
        this.fcm = fcm;
        this.jdbc = jdbc;
    }

    @Transactional
    public CreatedJob create(CreatePrintJobRequest request) {
        payloadValidator.validate(request.payload());
        UUID newId = UUID.randomUUID();
        String sql = """
                INSERT INTO print_job (id, seller_id, order_id, job_type, payload_json, status, idempotency_key,
                    open_cash_drawer, cut_paper, copies)
                VALUES (?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT (seller_id, idempotency_key) DO NOTHING
                RETURNING id
                """;
        UUID insertedId = jdbc.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                newId, request.sellerId(), request.orderId(), request.jobType().name(), request.payload().toString(),
                request.idempotencyKey(), request.openCashDrawer(), request.cutPaper(), request.copies());
        PrintJob job = jobs.findById(insertedId == null ? jobs.findBySellerIdAndIdempotencyKey(
                request.sellerId(), request.idempotencyKey()).orElseThrow().getId() : insertedId)
                .orElseThrow(() -> new IllegalStateException("Inserted print job was not found"));
        boolean created = insertedId != null;
        if (created) {
            logService.add(job, JobStatus.PENDING, "Job created", null);
            dispatchAfterCommit(job.getId(), job.getSellerId());
        }
        return new CreatedJob(toResponse(job), created);
    }

    @Transactional(readOnly = true)
    public Page<PrintJobResponse> list(String sellerId, JobStatus status, Pageable pageable) {
        Page<PrintJob> page = status == null ? jobs.findBySellerId(sellerId, pageable)
                : jobs.findBySellerIdAndStatus(sellerId, status, pageable);
        return page.map(PrintJobService::toResponse);
    }

    @Transactional(readOnly = true)
    public PrintJobResponse get(UUID id) {
        return toResponse(findJob(id));
    }

    @Transactional
    public PrintJobResponse cancel(UUID id) {
        PrintJob job = findJob(id);
        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.DISPATCHED) {
            throw conflict("Only pending or dispatched jobs can be cancelled");
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        logService.add(job, JobStatus.CANCELLED, "Job cancelled", null);
        return toResponse(job);
    }

    @Transactional
    public PrintJobResponse retry(UUID id) {
        PrintJob job = findJob(id);
        if (job.getStatus() != JobStatus.FAILED) throw conflict("Only failed jobs can be retried");
        job.setStatus(JobStatus.PENDING);
        job.setRetryCount(job.getRetryCount() + 1);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setDispatchedAt(null);
        job.setCompletedAt(null);
        logService.add(job, JobStatus.PENDING, "Job manually retried", null);
        dispatchAfterCommit(job.getId(), job.getSellerId());
        return toResponse(job);
    }

    @Transactional
    public List<PrintJobResponse> claim(DevicePrincipal principal, int requestedLimit) {
        AppDevice device = deviceService.requireActive(principal.deviceId());
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        List<PrintJob> claimed = jobs.claimable(device.getSellerId(), device.getId(), limit);
        Instant now = Instant.now();
        for (PrintJob job : claimed) {
            job.setStatus(JobStatus.DISPATCHED);
            job.setDevice(device);
            job.setDispatchedAt(now);
            logService.add(job, JobStatus.DISPATCHED, "Job claimed by device", device);
        }
        jobs.saveAll(claimed);
        return claimed.stream().map(PrintJobService::toResponse).toList();
    }

    @Transactional
    public PrintJobResponse updateStatus(DevicePrincipal principal, UUID id, UpdateJobStatusRequest request) {
        if (request.status() != JobStatus.PRINTING && request.status() != JobStatus.SUCCESS
                && request.status() != JobStatus.FAILED) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "Device status must be PRINTING, SUCCESS, or FAILED");
        }
        AppDevice device = deviceService.requireActive(principal.deviceId());
        PrintJob job = findJob(id);
        if (job.getDevice() == null || !job.getDevice().getId().equals(device.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "JOB_NOT_OWNED", "Job is not assigned to this device");
        }
        boolean legal = (job.getStatus() == JobStatus.DISPATCHED && request.status() == JobStatus.PRINTING)
                || (job.getStatus() == JobStatus.PRINTING
                && (request.status() == JobStatus.SUCCESS || request.status() == JobStatus.FAILED));
        if (!legal) throw conflict("Illegal job status transition");
        job.setStatus(request.status());
        if (request.status() == JobStatus.SUCCESS || request.status() == JobStatus.FAILED) {
            job.setCompletedAt(Instant.now());
        }
        job.setErrorCode(request.status() == JobStatus.FAILED ? request.errorCode() : null);
        job.setErrorMessage(request.status() == JobStatus.FAILED ? request.errorMessage() : null);
        logService.add(job, request.status(), safeMessage(request), device);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public Page<PrintJobResponse> history(DevicePrincipal principal, Pageable pageable) {
        deviceService.requireActive(principal.deviceId());
        return jobs.findByDeviceIdAndSellerId(principal.deviceId(), principal.sellerId(), pageable)
                .map(PrintJobService::toResponse);
    }

    @Transactional
    public void retryStaleJobs() {
        List<PrintJob> staleJobs = jobs.findStaleDispatchedForUpdate(Instant.now().minusSeconds(120));
        for (PrintJob job : staleJobs) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setDispatchedAt(null);
            job.setDevice(null);
            if (job.getRetryCount() > job.getMaxRetries()) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorCode("MAX_RETRIES");
                job.setErrorMessage("Maximum dispatch retries exceeded");
                job.setCompletedAt(Instant.now());
                logService.add(job, JobStatus.FAILED, "Maximum dispatch retries exceeded", null);
            } else {
                job.setStatus(JobStatus.PENDING);
                logService.add(job, JobStatus.PENDING, "Dispatch timed out; returned to queue", null);
            }
        }
    }

    @Transactional
    public int archiveOldJobs() {
        return jdbc.update("""
                WITH archived AS (
                    INSERT INTO print_job_archive (id, seller_id, archived_job)
                    SELECT id, seller_id, to_jsonb(j) FROM print_job j
                    WHERE created_at < now() - interval '90 days'
                    ON CONFLICT (id) DO NOTHING
                    RETURNING id
                ), removed_logs AS (
                    DELETE FROM print_log WHERE job_id IN (SELECT id FROM archived)
                )
                DELETE FROM print_job WHERE id IN (SELECT id FROM archived)
                """);
    }

    private void dispatchAfterCommit(UUID jobId, String sellerId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (AppDevice device : devices.findBySellerIdAndActiveTrue(sellerId)) {
                    if (device.getFcmToken() == null || device.getFcmToken().isBlank()) continue;
                    FcmPushService.Result result = fcm.sendNewJob(device.getFcmToken(), jobId.toString());
                    if (result == FcmPushService.Result.INVALID_TOKEN) {
                        deviceService.deactivateFcmToken(device.getId());
                    } else if (result == FcmPushService.Result.FAILED) {
                        log.warn("Unable to send new-job notification for job {}", jobId);
                    }
                }
            }
        });
    }

    private PrintJob findJob(UUID id) {
        return jobs.findById(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Print job not found"));
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_JOB_STATE", message);
    }

    private static String safeMessage(UpdateJobStatusRequest request) {
        return request.status() == JobStatus.FAILED ? "Print failed" : "Print status updated";
    }

    private static PrintJobResponse toResponse(PrintJob job) {
        return new PrintJobResponse(job.getId(), job.getSellerId(),
                job.getDevice() == null ? null : job.getDevice().getId(), job.getOrderId(), job.getJobType(),
                job.getPayloadJson(), job.getStatus(), job.getRetryCount(), job.getMaxRetries(), job.getErrorCode(),
                job.getErrorMessage(), job.isOpenCashDrawer(), job.isCutPaper(), job.getCopies(), job.getCreatedAt(),
                job.getDispatchedAt(), job.getCompletedAt());
    }

    public record CreatedJob(PrintJobResponse job, boolean created) {
    }
}

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
import com.bizbitenow.printservice.printer.entity.Printer;
import com.bizbitenow.printservice.printer.repository.PrinterRepository;
import com.bizbitenow.printservice.security.DevicePrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Base64;
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
    private final PrinterRepository printers;
    private final EscPosReceiptGenerator escPos;

    public PrintJobService(PrintJobRepository jobs, AppDeviceRepository devices, DeviceService deviceService,
                           ReceiptPayloadValidator payloadValidator, PrintLogService logService,
                           FcmPushService fcm, JdbcTemplate jdbc, PrinterRepository printers,
                           EscPosReceiptGenerator escPos) {
        this.jobs = jobs;
        this.devices = devices;
        this.deviceService = deviceService;
        this.payloadValidator = payloadValidator;
        this.logService = logService;
        this.fcm = fcm;
        this.jdbc = jdbc;
        this.printers = printers;
        this.escPos = escPos;
    }

    @Transactional
    public CreatedJob create(CreatePrintJobRequest request) {
        payloadValidator.validate(request.payload());
        UUID newId = UUID.randomUUID();
        String sql = """
                INSERT INTO print_job (id, seller_id, printer_id, order_id, job_type, payload_json, status, idempotency_key,
                    open_cash_drawer, cut_paper, copies)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?, ?)
                ON CONFLICT (seller_id, idempotency_key) DO NOTHING
                RETURNING id
                """;
        if (request.printerId() != null) {
            Printer printer = requireSellerPrinter(request.printerId(), request.sellerId());
            if (!"ESC_POS_BASE64".equals(request.payload().path("format").asText())
                    && request.payload().path("paperWidthMm").asInt() != printer.getPaperWidthMm()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PAYLOAD",
                        "Receipt paperWidthMm must match the selected printer");
            }
        }
        UUID insertedId = jdbc.query(sql, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                newId, request.sellerId(), request.printerId(), request.orderId(), request.jobType().name(), request.payload().toString(),
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
        return page.map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PrintJobResponse get(UUID id) {
        return toResponse(findJob(id));
    }

    @Transactional
    public PrintJobResponse cancel(UUID id) {
        PrintJob job = jobs.findByIdForUpdate(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Print job not found"));
        if (job.getStatus() != JobStatus.PENDING && job.getStatus() != JobStatus.DISPATCHED
                && job.getStatus() != JobStatus.SENT_TO_DEVICE) {
            throw conflict("Only pending or dispatched jobs can be cancelled");
        }
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        logService.add(job, JobStatus.CANCELLED, "Job cancelled", null);
        return toResponse(job);
    }

    @Transactional
    public PrintJobResponse retry(UUID id) {
        PrintJob job = jobs.findByIdForUpdate(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Print job not found"));
        if (job.getStatus() != JobStatus.FAILED) throw conflict("Only failed jobs can be retried");
        if (job.getRetryCount() >= 3) throw conflict("Maximum print retries exceeded");
        job.setStatus(JobStatus.PENDING);
        job.setRetryCount(job.getRetryCount() + 1);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setDispatchedAt(null);
        job.setCompletedAt(null);
        job.setPrintedAt(null);
        logService.add(job, JobStatus.PENDING, "Job manually retried", null);
        dispatchAfterCommit(job.getId(), job.getSellerId());
        return toResponse(job);
    }

    @Transactional
    public List<PrintJobResponse> claim(DevicePrincipal principal, int requestedLimit) {
        return claim(principal, requestedLimit, null, false);
    }

    @Transactional
    public List<PrintJobResponse> claimForPrinter(DevicePrincipal principal, UUID printerId, int requestedLimit) {
        Printer printer = requireDevicePrinter(printerId, principal);
        return claim(principal, requestedLimit, printer, true);
    }

    private List<PrintJobResponse> claim(DevicePrincipal principal, int requestedLimit, Printer printer,
                                         boolean newStatus) {
        AppDevice device = deviceService.requireActive(principal.deviceId());
        int limit = Math.min(Math.max(requestedLimit, 1), 100);
        List<PrintJob> claimed = jobs.claimable(device.getSellerId(), device.getId(),
                printer == null ? null : printer.getId(), limit);
        Instant now = Instant.now();
        for (PrintJob job : claimed) {
            job.setStatus(newStatus ? JobStatus.SENT_TO_DEVICE : JobStatus.DISPATCHED);
            job.setDevice(device);
            if (job.getPrinter() == null && printer != null) job.setPrinter(printer);
            job.setDispatchedAt(now);
            job.setPrintingStartedAt(null);
            logService.add(job, job.getStatus(), "Job claimed by device", device);
        }
        jobs.saveAll(claimed);
        return claimed.stream().map(this::toResponse).toList();
    }

    @Transactional
    public PrintJobResponse updateStatus(DevicePrincipal principal, UUID id, UpdateJobStatusRequest request) {
        if (request.status() != JobStatus.PRINTING && request.status() != JobStatus.SUCCESS
                && request.status() != JobStatus.FAILED) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "Device status must be PRINTING, SUCCESS, or FAILED");
        }
        if (request.status() == JobStatus.FAILED
                && (request.errorMessage() == null || request.errorMessage().isBlank())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "errorMessage is required when the device reports FAILED");
        }
        AppDevice device = deviceService.requireActive(principal.deviceId());
        PrintJob job = jobs.findByIdForUpdate(id).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Print job not found"));
        if (job.getDevice() == null || !job.getDevice().getId().equals(device.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "JOB_NOT_OWNED", "Job is not assigned to this device");
        }
        boolean legal = ((job.getStatus() == JobStatus.DISPATCHED || job.getStatus() == JobStatus.SENT_TO_DEVICE)
                && request.status() == JobStatus.PRINTING)
                || (job.getStatus() == JobStatus.PRINTING
                && (request.status() == JobStatus.SUCCESS || request.status() == JobStatus.FAILED));
        if (!legal) throw conflict("Illegal job status transition");
        Instant now = Instant.now();
        if (request.status() == JobStatus.PRINTING) {
            job.setStatus(JobStatus.PRINTING);
            job.setPrintingStartedAt(now);
            logService.add(job, JobStatus.PRINTING, safeMessage(request), device);
        } else if (request.status() == JobStatus.SUCCESS) {
            job.setStatus(JobStatus.SUCCESS);
            job.setPrintedAt(now);
            job.setCompletedAt(now);
            job.setPrintingStartedAt(null);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            logService.add(job, JobStatus.SUCCESS, safeMessage(request), device);
        } else {
            job.setErrorCode(request.errorCode());
            job.setErrorMessage(request.errorMessage());
            job.setPrintingStartedAt(null);
            logService.add(job, JobStatus.FAILED, safeMessage(request), device);
            if (job.getRetryCount() < 3) {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setStatus(JobStatus.PENDING);
                job.setDevice(null);
                job.setDispatchedAt(null);
                job.setCompletedAt(null);
                logService.add(job, JobStatus.PENDING, "Retry scheduled after device-reported failure", null);
                dispatchAfterCommit(job.getId(), job.getSellerId());
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setCompletedAt(now);
            }
        }
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public Page<PrintJobResponse> history(DevicePrincipal principal, Pageable pageable) {
        deviceService.requireActive(principal.deviceId());
        return jobs.findDeviceHistory(principal.deviceId(), principal.sellerId(), pageable)
                .map(this::toResponse);
    }

    @Transactional
    public void retryStaleJobs() {
        List<PrintJob> staleJobs = jobs.findStaleDispatchedForUpdate(Instant.now().minusSeconds(120));
        for (PrintJob job : staleJobs) {
            job.setRetryCount(job.getRetryCount() + 1);
            job.setDispatchedAt(null);
            job.setDevice(null);
            if (job.getRetryCount() >= job.getMaxRetries()) {
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
    public void failTimedOutPrintingJobs() {
        Instant now = Instant.now();
        List<PrintJob> staleJobs = jobs.findStalePrintingForUpdate(now.minusSeconds(60));
        for (PrintJob job : staleJobs) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorCode("PRINT_TIMEOUT");
            job.setErrorMessage("Device did not confirm the print within 60 seconds");
            job.setCompletedAt(now);
            job.setPrintingStartedAt(null);
            logService.add(job, JobStatus.FAILED, job.getErrorMessage(), job.getDevice());
        }
    }

    @Transactional(readOnly = true)
    public byte[] testPayload(UUID printerId, DevicePrincipal principal) {
        return escPos.testPayload(requireDevicePrinter(printerId, principal).getPaperWidthMm());
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

    private Printer requireSellerPrinter(UUID printerId, String sellerId) {
        Printer printer = printers.findById(printerId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRINTER_NOT_FOUND", "Printer not found"));
        if (!printer.getSellerId().equals(sellerId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PRINTER_NOT_OWNED",
                    "Printer does not belong to this seller");
        }
        return printer;
    }

    private Printer requireDevicePrinter(UUID printerId, DevicePrincipal principal) {
        Printer printer = printers.findById(printerId).orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "PRINTER_NOT_FOUND", "Printer not found"));
        if (!printer.getDevice().getId().equals(principal.deviceId())
                || !printer.getSellerId().equals(principal.sellerId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "PRINTER_NOT_OWNED",
                    "Printer is not assigned to this device");
        }
        return printer;
    }

    private BusinessException conflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "INVALID_JOB_STATE", message);
    }

    private static String safeMessage(UpdateJobStatusRequest request) {
        return request.status() == JobStatus.FAILED ? request.errorMessage() : "Print status updated";
    }

    private PrintJobResponse toResponse(PrintJob job) {
        return new PrintJobResponse(job.getId(), job.getSellerId(),
                job.getDevice() == null ? null : job.getDevice().getId(), job.getOrderId(), job.getJobType(),
                job.getPayloadJson(), job.getStatus(), job.getRetryCount(), job.getMaxRetries(), job.getErrorCode(),
                job.getErrorMessage(), job.isOpenCashDrawer(), job.isCutPaper(), job.getCopies(), job.getCreatedAt(),
                job.getUpdatedAt(), job.getDispatchedAt(), job.getPrintedAt(), job.getCompletedAt(),
                job.getPrinter() == null ? null : job.getPrinter().getId(),
                Base64.getEncoder().encodeToString(escPos.generate(job.getPayloadJson(), job.isCutPaper())));
    }

    public record CreatedJob(PrintJobResponse job, boolean created) {
    }
}

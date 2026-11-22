package com.bizbitenow.printservice.job.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.common.JobOutcome;
import com.bizbitenow.printservice.common.ReceiptPayloadValidator;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.device.service.DeviceService;
import com.bizbitenow.printservice.device.service.FcmPushService;
import com.bizbitenow.printservice.job.dto.CreatePrintJobRequest;
import com.bizbitenow.printservice.job.dto.OfflineJobBatchRequest;
import com.bizbitenow.printservice.job.dto.OfflineJobBatchResponse;
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
import org.springframework.beans.factory.annotation.Value;
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
    @Value("${app.jobs.max-retries:3}")
    private int configuredMaxRetries = 3;
    @Value("${app.jobs.retry-delay-seconds:30}")
    private long retryDelaySeconds = 30;
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
                    open_cash_drawer, cut_paper, copies, max_retries)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'QUEUED', ?, ?, ?, ?, ?)
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
                request.idempotencyKey(), request.openCashDrawer(), request.cutPaper(), request.copies(), configuredMaxRetries);
        PrintJob job = jobs.findById(insertedId == null ? jobs.findBySellerIdAndIdempotencyKey(
                request.sellerId(), request.idempotencyKey()).orElseThrow().getId() : insertedId)
                .orElseThrow(() -> new IllegalStateException("Inserted print job was not found"));
        boolean created = insertedId != null;
        if (created) {
            logService.add(job, JobStatus.QUEUED, "Job created", null);
            dispatchAfterCommit(job.getId(), job.getSellerId());
        }
        return new CreatedJob(toResponse(job), created);
    }

    @Transactional(readOnly = true)
    public Page<PrintJobResponse> list(String sellerId, JobStatus status, Pageable pageable) {
        JobStatus canonicalFilter = canonicalStatus(status);
        Page<PrintJob> page = canonicalFilter == null ? jobs.findBySellerId(sellerId, pageable)
                : jobs.findBySellerIdAndStatus(sellerId, canonicalFilter, pageable);
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
        if (job.getStatus() != JobStatus.QUEUED && job.getStatus() != JobStatus.SENT_TO_PRINTER) {
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
        if (job.getRetryCount() >= job.getMaxRetries()) throw conflict("Maximum print retries exceeded");
        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(job.getRetryCount() + 1);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setDispatchedAt(null);
        job.setCompletedAt(null);
        job.setPrintedAt(null);
        job.setNextRetryAt(null);
        logService.add(job, JobStatus.QUEUED, "Job manually retried", null);
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
            job.setStatus(JobStatus.SENT_TO_PRINTER);
            job.setClientUpdatedAt(now);
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
    public OfflineJobBatchResponse syncOfflineResults(DevicePrincipal principal, OfflineJobBatchRequest request) {
        AppDevice device = deviceService.requireActive(principal.deviceId());
        List<OfflineJobBatchResponse.Item> results = new java.util.ArrayList<>();
        for (OfflineJobBatchRequest.Result result : request.results()) {
            int inserted = jdbc.update("""
                    INSERT INTO print_job_sync_event(client_id, job_id, client_timestamp)
                    VALUES (?, ?, ?) ON CONFLICT (client_id) DO NOTHING
                    """, result.clientId(), result.jobId(), java.sql.Timestamp.from(result.clientTimestamp()));
            if (inserted == 0) {
                UUID originalJobId = jdbc.queryForObject(
                        "SELECT job_id FROM print_job_sync_event WHERE client_id = ?", UUID.class, result.clientId());
                if (!result.jobId().equals(originalJobId)) {
                    throw conflict("clientId has already been used for a different job");
                }
                PrintJob duplicateJob = jobs.findById(result.jobId()).orElse(null);
                results.add(new OfflineJobBatchResponse.Item(result.clientId(), result.jobId(),
                        duplicateJob == null ? null : duplicateJob.getStatus(), false, true));
                continue;
            }
            PrintJob job = jobs.findByIdForUpdate(result.jobId()).orElseThrow(() ->
                    new BusinessException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "Print job not found"));
            if (job.getDevice() == null || !job.getDevice().getId().equals(device.getId())
                    || !job.getSellerId().equals(device.getSellerId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "JOB_NOT_OWNED", "Job is not assigned to this device");
            }
            if (job.getClientUpdatedAt() != null && !result.clientTimestamp().isAfter(job.getClientUpdatedAt())) {
                results.add(new OfflineJobBatchResponse.Item(result.clientId(), result.jobId(), job.getStatus(), false, false));
                continue;
            }
            boolean applied = applyOfflineResult(job, device, result);
            if (applied) job.setClientUpdatedAt(result.clientTimestamp());
            results.add(new OfflineJobBatchResponse.Item(result.clientId(), result.jobId(), job.getStatus(), applied, false));
        }
        return new OfflineJobBatchResponse(List.copyOf(results));
    }

    private boolean applyOfflineResult(PrintJob job, AppDevice device, OfflineJobBatchRequest.Result result) {
        JobStatus target = canonicalStatus(result.status());
        if (target == JobStatus.FAILED) {
            if (result.errorMessage() == null || result.errorMessage().isBlank()) {
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS", "errorMessage is required when status is FAILED");
            }
            if (job.getStatus() != JobStatus.SENT_TO_PRINTER) throw conflict("Illegal job status transition");
            Instant now = result.clientTimestamp();
            job.setErrorCode(result.errorCode());
            job.setErrorMessage(result.errorMessage());
            job.setPrintingStartedAt(null);
            logService.add(job, JobStatus.FAILED, result.errorMessage(), device);
            if (job.getDeviceReportedAt() == null && job.getRetryCount() < job.getMaxRetries()) {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setStatus(JobStatus.QUEUED);
                job.setDevice(null);
                job.setDispatchedAt(null);
                job.setCompletedAt(null);
                job.setNextRetryAt(now.plusSeconds(retryDelaySeconds));
                logService.add(job, JobStatus.QUEUED, "Retry scheduled after offline failure sync", null);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setCompletedAt(now);
            }
            return true;
        }
        if (target == JobStatus.SENT_TO_PRINTER && job.getStatus() == JobStatus.SENT_TO_PRINTER) {
            if (!hasPrinterWriteEvidence(result.connected(), result.bytesWritten(), result.flushOk())) {
                throw invalidSentEvidence();
            }
            if (job.getDeviceReportedAt() != null) return false;
            recordUnconfirmedSend(job, device, result.connected(), result.bytesWritten(), result.flushOk(), result.clientTimestamp());
            return true;
        }
        if (target != JobStatus.PRINTED_CONFIRMED || job.getStatus() != JobStatus.SENT_TO_PRINTER) {
            throw conflict("Offline sync only accepts SENT_TO_PRINTER, FAILED, or PRINTED_CONFIRMED from SENT_TO_PRINTER");
        }
        if (!hasPrinterWriteEvidence(result.connected(), result.bytesWritten(), result.flushOk())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRINT_CONFIRMATION_REQUIRED",
                    "PRINTED_CONFIRMED requires connected=true, bytesWritten>0, and flushOk=true");
        }
        job.setStatus(JobStatus.PRINTED_CONFIRMED);
        job.setPrinterConnected(true);
        job.setBytesWritten(result.bytesWritten());
        job.setFlushOk(true);
        job.setPrintedAt(result.clientTimestamp());
        job.setCompletedAt(result.clientTimestamp());
        logService.add(job, JobStatus.PRINTED_CONFIRMED, "Offline print confirmation synced", device);
        return true;
    }

    @Transactional
    public PrintJobResponse updateStatus(DevicePrincipal principal, UUID id, UpdateJobStatusRequest request) {
        if (request.status() != JobStatus.PRINTING && request.status() != JobStatus.SENT_TO_PRINTER && request.status() != JobStatus.SUCCESS
                && request.status() != JobStatus.PRINTED_CONFIRMED && request.status() != JobStatus.FAILED) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_STATUS",
                    "Device status must be PRINTING, SENT_TO_PRINTER, SUCCESS, or FAILED");
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
        JobStatus target = canonicalStatus(request.status());
        if (request.status() == JobStatus.SENT_TO_PRINTER) {
            if (job.getStatus() != JobStatus.SENT_TO_PRINTER) throw conflict("Illegal job status transition");
            if (!hasPrinterWriteEvidence(request.connected(), request.bytesWritten(), request.flushOk())) {
                throw invalidSentEvidence();
            }
            if (job.getDeviceReportedAt() == null) {
                Instant reportedAt = Instant.now();
                recordUnconfirmedSend(job, device, request.connected(), request.bytesWritten(), request.flushOk(), reportedAt);
                job.setClientUpdatedAt(reportedAt);
            }
            return toResponse(job);
        }
        if (job.getStatus() == target && (target == JobStatus.PRINTED_CONFIRMED || target == JobStatus.FAILED)) {
            return toResponse(job);
        }
        boolean printingCallback = request.status() == JobStatus.PRINTING;
        boolean successCallback = target == JobStatus.PRINTED_CONFIRMED;
        boolean legacySuccess = request.status() == JobStatus.SUCCESS;
        boolean legal = job.getStatus() == JobStatus.SENT_TO_PRINTER && (printingCallback || successCallback || request.status() == JobStatus.FAILED)
                && (!legacySuccess || job.getPrintingStartedAt() != null);
        if (!legal) throw conflict("Illegal job status transition");
        if (successCallback && !hasPrinterWriteEvidence(request.connected(), request.bytesWritten(), request.flushOk())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRINT_CONFIRMATION_REQUIRED",
                    "SUCCESS requires connected=true, bytesWritten>0, and flushOk=true");
        }
        Instant now = Instant.now();
        job.setClientUpdatedAt(now);
        if (printingCallback) {
            job.setPrintingStartedAt(now);
            logService.add(job, JobStatus.PRINTING, safeMessage(request), device);
        } else if (successCallback) {
            job.setStatus(JobStatus.PRINTED_CONFIRMED);
            job.setPrinterConnected(request.connected());
            job.setBytesWritten(request.bytesWritten());
            job.setFlushOk(request.flushOk());
            job.setPrintedAt(now);
            job.setCompletedAt(now);
            job.setPrintingStartedAt(null);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            logService.add(job, JobStatus.PRINTED_CONFIRMED, safeMessage(request), device);
        } else {
            job.setErrorCode(request.errorCode());
            job.setErrorMessage(request.errorMessage());
            job.setPrintingStartedAt(null);
            logService.add(job, JobStatus.FAILED, safeMessage(request), device);
            if (job.getDeviceReportedAt() == null && job.getRetryCount() < job.getMaxRetries()) {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setStatus(JobStatus.QUEUED);
                job.setDevice(null);
                job.setDispatchedAt(null);
                job.setCompletedAt(null);
                job.setNextRetryAt(now.plusSeconds(retryDelaySeconds));
                logService.add(job, JobStatus.QUEUED, "Retry scheduled after device-reported failure", null);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setCompletedAt(now);
                job.setNextRetryAt(null);
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
            job.setDispatchedAt(null);
            job.setDevice(null);
            job.setErrorCode("DISPATCH_TIMEOUT");
            job.setErrorMessage("Printer device did not claim the queued job in time");
            if (job.getRetryCount() >= job.getMaxRetries()) {
                job.setStatus(JobStatus.FAILED);
                job.setErrorCode("MAX_RETRIES");
                job.setErrorMessage("Maximum dispatch retries exceeded");
                job.setCompletedAt(Instant.now());
                logService.add(job, JobStatus.FAILED, "Maximum dispatch retries exceeded", null);
            } else {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setStatus(JobStatus.QUEUED);
                job.setNextRetryAt(Instant.now().plusSeconds(retryDelaySeconds));
                logService.add(job, JobStatus.QUEUED, "Dispatch timed out; retry scheduled", null);
            }
        }
    }

    @Transactional
    public void failTimedOutPrintingJobs() {
        Instant now = Instant.now();
        List<PrintJob> staleJobs = jobs.findStalePrintingForUpdate(now.minusSeconds(60));
        for (PrintJob job : staleJobs) {
            job.setErrorCode("PRINT_TIMEOUT");
            job.setErrorMessage("Device did not confirm the print within 60 seconds");
            job.setPrintingStartedAt(null);
            logService.add(job, JobStatus.FAILED, job.getErrorMessage(), job.getDevice());
            if (job.getRetryCount() < job.getMaxRetries()) {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setStatus(JobStatus.QUEUED);
                job.setDevice(null);
                job.setDispatchedAt(null);
                job.setCompletedAt(null);
                job.setNextRetryAt(now.plusSeconds(retryDelaySeconds));
                logService.add(job, JobStatus.QUEUED, "Retry scheduled after print timeout", null);
            } else {
                job.setStatus(JobStatus.FAILED);
                job.setCompletedAt(now);
                job.setNextRetryAt(null);
            }
        }
    }

    @Transactional
    public int processDueRetries() {
        List<PrintJob> due = jobs.findDueRetriesForUpdate(Instant.now());
        for (PrintJob job : due) {
            job.setNextRetryAt(null);
            dispatchAfterCommit(job.getId(), job.getSellerId());
        }
        return due.size();
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
                job.getPayloadJson(), legacyStatus(job), job.getStatus(), job.getRetryCount(), job.getMaxRetries(), job.getErrorCode(),
                job.getErrorMessage(), job.isOpenCashDrawer(), job.isCutPaper(), job.getCopies(), job.getCreatedAt(),
                job.getUpdatedAt(), job.getDispatchedAt(), job.getPrintedAt(), job.getCompletedAt(),
                job.getPrinter() == null ? null : job.getPrinter().getId(),
                Base64.getEncoder().encodeToString(escPos.generate(job.getPayloadJson(), job.isCutPaper())), job.getNextRetryAt(),
                outcome(job));
    }

    private static JobOutcome outcome(PrintJob job) {
        return switch (job.getStatus()) {
            case QUEUED, PENDING -> JobOutcome.PENDING;
            case SENT_TO_PRINTER -> job.getDeviceReportedAt() == null ? JobOutcome.IN_PROGRESS : JobOutcome.SENT_UNCONFIRMED;
            case PRINTED_CONFIRMED, SUCCESS -> JobOutcome.PRINTED_CONFIRMED;
            case FAILED -> JobOutcome.FAILED;
            case CANCELLED -> JobOutcome.CANCELLED;
            case DISPATCHED, SENT_TO_DEVICE, PRINTING -> JobOutcome.IN_PROGRESS;
        };
    }

    private void recordUnconfirmedSend(PrintJob job, AppDevice device, Boolean connected, Long bytesWritten,
                                       Boolean flushOk, Instant reportedAt) {
        job.setPrinterConnected(connected);
        job.setBytesWritten(bytesWritten);
        job.setFlushOk(flushOk);
        job.setDeviceReportedAt(reportedAt);
        job.setPrintingStartedAt(null);
        logService.add(job, JobStatus.SENT_TO_PRINTER, "Device sent data; printer status was not confirmed", device);
    }

    private static boolean hasPrinterWriteEvidence(Boolean connected, Long bytesWritten, Boolean flushOk) {
        return Boolean.TRUE.equals(connected) && bytesWritten != null && bytesWritten > 0 && Boolean.TRUE.equals(flushOk);
    }

    private BusinessException invalidSentEvidence() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "SEND_EVIDENCE_REQUIRED",
                "SENT_TO_PRINTER requires connected=true, bytesWritten>0, and flushOk=true");
    }

    private static JobStatus legacyStatus(PrintJob job) {
        return switch (job.getStatus()) {
            case QUEUED -> JobStatus.PENDING;
            case SENT_TO_PRINTER -> job.getPrintingStartedAt() != null ? JobStatus.PRINTING
                    : job.getPrinter() != null ? JobStatus.SENT_TO_DEVICE : JobStatus.DISPATCHED;
            case PRINTED_CONFIRMED -> JobStatus.SUCCESS;
            default -> job.getStatus();
        };
    }

    private static JobStatus canonicalStatus(JobStatus status) {
        if (status == null) return null;
        return switch (status) {
            case PENDING -> JobStatus.QUEUED;
            case DISPATCHED, SENT_TO_DEVICE, PRINTING -> JobStatus.SENT_TO_PRINTER;
            case SUCCESS -> JobStatus.PRINTED_CONFIRMED;
            default -> status;
        };
    }

    public record CreatedJob(PrintJobResponse job, boolean created) {
    }
}

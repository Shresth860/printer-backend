package com.bizbitenow.printservice.job.service;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.common.ReceiptPayloadValidator;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.device.repository.AppDeviceRepository;
import com.bizbitenow.printservice.device.service.DeviceService;
import com.bizbitenow.printservice.device.service.FcmPushService;
import com.bizbitenow.printservice.job.dto.CreatePrintJobRequest;
import com.bizbitenow.printservice.job.dto.UpdateJobStatusRequest;
import com.bizbitenow.printservice.job.entity.PrintJob;
import com.bizbitenow.printservice.job.repository.PrintJobRepository;
import com.bizbitenow.printservice.printer.repository.PrinterRepository;
import com.bizbitenow.printservice.security.DevicePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrintJobServiceTest {
    private PrintJobRepository jobs;
    private DeviceService deviceService;
    private PrintLogService logService;
    private JdbcTemplate jdbc;
    private PrintJobService service;
    private EscPosReceiptGenerator escPos;

    @BeforeEach
    void setUp() {
        jobs = mock(PrintJobRepository.class);
        AppDeviceRepository devices = mock(AppDeviceRepository.class);
        deviceService = mock(DeviceService.class);
        logService = mock(PrintLogService.class);
        jdbc = mock(JdbcTemplate.class);
        escPos = new EscPosReceiptGenerator();
        service = new PrintJobService(jobs, devices, deviceService, mock(ReceiptPayloadValidator.class),
                logService, mock(FcmPushService.class), jdbc, mock(PrinterRepository.class), escPos);
    }

    @Test
    void stateMachineRejectsSkippingPrinting() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.SENT_TO_PRINTER);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateStatus(new DevicePrincipal(device.getId(), "seller"), jobId,
                        new UpdateJobStatusRequest(JobStatus.SUCCESS, null, null)));

        assertEquals(409, error.getStatus().value());
        assertEquals(JobStatus.SENT_TO_PRINTER, job.getStatus());
    }

    @Test
    void stateMachineAllowsPrintingThenSuccess() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.SENT_TO_PRINTER);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        DevicePrincipal principal = new DevicePrincipal(device.getId(), "seller");

        service.updateStatus(principal, jobId, new UpdateJobStatusRequest(JobStatus.PRINTING, null, null));
        assertEquals(JobStatus.SENT_TO_PRINTER, job.getStatus());
        service.updateStatus(principal, jobId, new UpdateJobStatusRequest(JobStatus.SUCCESS, null, null, true, 42L, true));
        assertEquals(JobStatus.PRINTED_CONFIRMED, job.getStatus());
        assertNotNull(job.getCompletedAt());
        assertNotNull(job.getPrintedAt());
    }

    @Test
    void rejectsSuccessWithoutPrinterConfirmationEvidence() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.SENT_TO_PRINTER);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateStatus(new DevicePrincipal(device.getId(), "seller"), jobId,
                        new UpdateJobStatusRequest(JobStatus.PRINTED_CONFIRMED, null, null)));
        assertEquals(400, error.getStatus().value());
        assertEquals(JobStatus.SENT_TO_PRINTER, job.getStatus());
    }

    @Test
    void duplicateSuccessCallbackIsIdempotentAndDoesNotReplaceEvidence() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.PRINTED_CONFIRMED);
        job.setPrinterConnected(true);
        job.setBytesWritten(42L);
        job.setFlushOk(true);
        var printedAt = java.time.Instant.parse("2026-11-22T00:00:00Z");
        job.setPrintedAt(printedAt);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));

        var response = service.updateStatus(new DevicePrincipal(device.getId(), "seller"), jobId,
                new UpdateJobStatusRequest(JobStatus.SUCCESS, null, null));

        assertEquals(JobStatus.SUCCESS, response.status());
        assertEquals(printedAt, job.getPrintedAt());
        assertEquals(42L, job.getBytesWritten());
        assertEquals(Boolean.TRUE, job.getPrinterConnected());
        assertEquals(Boolean.TRUE, job.getFlushOk());
        verify(logService, never()).add(any(), any(), anyString(), any());
    }

    @Test
    void deviceFailureRetriesThreeTimesThenRemainsFailed() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.SENT_TO_PRINTER);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findByIdForUpdate(jobId)).thenReturn(Optional.of(job));
        DevicePrincipal principal = new DevicePrincipal(device.getId(), "seller");

        TransactionSynchronizationManager.initSynchronization();
        var retry = service.updateStatus(principal, jobId,
                new UpdateJobStatusRequest(JobStatus.FAILED, "PAPER_OUT", "Paper roll empty"));
        TransactionSynchronizationManager.clearSynchronization();
        assertEquals(JobStatus.PENDING, retry.status());
        assertEquals(1, retry.retryCount());
        assertEquals("Paper roll empty", retry.errorMessage());
        assertNotNull(retry.nextRetryAt());

        job.setDevice(device);
        job.setStatus(JobStatus.SENT_TO_PRINTER);
        job.setRetryCount(3);
        var terminal = service.updateStatus(principal, jobId,
                new UpdateJobStatusRequest(JobStatus.FAILED, "PAPER_OUT", "Paper roll empty"));
        assertEquals(JobStatus.FAILED, terminal.status());
        assertEquals(3, terminal.retryCount());
        assertNotNull(terminal.completedAt());
    }

    @Test
    void dueRetryIsReleasedForQueueProcessing() {
        UUID jobId = UUID.randomUUID();
        PrintJob job = job(jobId, null, JobStatus.QUEUED);
        job.setNextRetryAt(java.time.Instant.now().minusSeconds(1));
        when(jobs.findDueRetriesForUpdate(any())).thenReturn(java.util.List.of(job));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertEquals(1, service.processDueRetries());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertNull(job.getNextRetryAt());
        assertEquals(JobStatus.QUEUED, job.getStatus());
    }

    @Test
    void stalePrintingJobsScheduleRetryWithoutMarkingSuccess() {
        UUID jobId = UUID.randomUUID();
        PrintJob job = job(jobId, null, JobStatus.SENT_TO_PRINTER);
        when(jobs.findStalePrintingForUpdate(any())).thenReturn(java.util.List.of(job));

        service.failTimedOutPrintingJobs();

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals(1, job.getRetryCount());
        assertEquals("PRINT_TIMEOUT", job.getErrorCode());
        assertNotNull(job.getNextRetryAt());
        assertNull(job.getPrintedAt());
        verify(logService).add(job, JobStatus.FAILED,
                "Device did not confirm the print within 60 seconds", null);
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingJobWithoutSecondLog() {
        var mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("paperWidthMm", 58);
        payload.putArray("blocks").addObject().put("type", "FEED").put("lines", 1);
        CreatePrintJobRequest request = new CreatePrintJobRequest("seller", "order", com.bizbitenow.printservice.common.JobType.RECEIPT,
                payload, false, true, 1, "same-key");
        UUID id = UUID.randomUUID();
        PrintJob existing = job(id, null, JobStatus.QUEUED);
        existing.setSellerId("seller");
        existing.setIdempotencyKey("same-key");
        when(jdbc.query(anyString(), ArgumentMatchers.<ResultSetExtractor<UUID>>any(), any(Object[].class)))
                .thenReturn(null);
        when(jobs.findBySellerIdAndIdempotencyKey("seller", "same-key")).thenReturn(Optional.of(existing));
        when(jobs.findById(id)).thenReturn(Optional.of(existing));

        var response = service.create(request);

        assertFalse(response.created());
        assertEquals(id, response.job().id());
        verify(logService, never()).add(any(), any(), anyString(), any());
    }

    private AppDevice device(UUID id, String seller) {
        AppDevice device = new AppDevice();
        device.setId(id);
        device.setSellerId(seller);
        device.setActive(true);
        return device;
    }

    private PrintJob job(UUID id, AppDevice device, JobStatus status) {
        PrintJob job = new PrintJob();
        job.setId(id);
        job.setDevice(device);
        job.setSellerId("seller");
        job.setStatus(status);
        ObjectMapper mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("paperWidthMm", 58);
        payload.putArray("blocks").addObject()
                .put("type", "TEXT").put("text", "receipt").put("align", "CENTER")
                .put("bold", true).put("size", "NORMAL");
        job.setPayloadJson(payload);
        return job;
    }
}

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
import com.bizbitenow.printservice.security.DevicePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

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

    @BeforeEach
    void setUp() {
        jobs = mock(PrintJobRepository.class);
        AppDeviceRepository devices = mock(AppDeviceRepository.class);
        deviceService = mock(DeviceService.class);
        logService = mock(PrintLogService.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PrintJobService(jobs, devices, deviceService, mock(ReceiptPayloadValidator.class),
                logService, mock(FcmPushService.class), jdbc);
    }

    @Test
    void stateMachineRejectsSkippingPrinting() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.DISPATCHED);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateStatus(new DevicePrincipal(device.getId(), "seller"), jobId,
                        new UpdateJobStatusRequest(JobStatus.SUCCESS, null, null)));

        assertEquals(409, error.getStatus().value());
        assertEquals(JobStatus.DISPATCHED, job.getStatus());
    }

    @Test
    void stateMachineAllowsPrintingThenSuccess() {
        UUID jobId = UUID.randomUUID();
        AppDevice device = device(jobId, "seller");
        PrintJob job = job(jobId, device, JobStatus.DISPATCHED);
        when(deviceService.requireActive(device.getId())).thenReturn(device);
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        DevicePrincipal principal = new DevicePrincipal(device.getId(), "seller");

        service.updateStatus(principal, jobId, new UpdateJobStatusRequest(JobStatus.PRINTING, null, null));
        assertEquals(JobStatus.PRINTING, job.getStatus());
        service.updateStatus(principal, jobId, new UpdateJobStatusRequest(JobStatus.SUCCESS, null, null));
        assertEquals(JobStatus.SUCCESS, job.getStatus());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingJobWithoutSecondLog() {
        var mapper = new ObjectMapper();
        var payload = mapper.createObjectNode().put("paperWidthMm", 58);
        payload.putArray("blocks").addObject().put("type", "FEED").put("lines", 1);
        CreatePrintJobRequest request = new CreatePrintJobRequest("seller", "order", com.bizbitenow.printservice.common.JobType.RECEIPT,
                payload, false, true, 1, "same-key");
        UUID id = UUID.randomUUID();
        PrintJob existing = job(id, null, JobStatus.PENDING);
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
        return job;
    }
}

package com.bizbitenow.printservice.job.controller;

import com.bizbitenow.printservice.common.BusinessException;
import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.job.dto.CreatePrintJobRequest;
import com.bizbitenow.printservice.job.dto.PrintJobResponse;
import com.bizbitenow.printservice.job.dto.UpdateJobStatusRequest;
import com.bizbitenow.printservice.job.service.PrintJobService;
import com.bizbitenow.printservice.security.DevicePrincipal;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/jobs", "/api/print-jobs"})
@Validated
public class PrintJobController {
    private final PrintJobService jobService;

    public PrintJobController(PrintJobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @SecurityRequirement(name = "serviceKey")
    public ResponseEntity<PrintJobResponse> create(@Valid @RequestBody CreatePrintJobRequest request) {
        PrintJobService.CreatedJob created = jobService.create(request);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.job());
    }

    @GetMapping
    @SecurityRequirement(name = "serviceKey")
    public Page<PrintJobResponse> list(@RequestParam String sellerId, @RequestParam(required = false) JobStatus status,
                                      @RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return jobService.list(sellerId, status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "serviceKey")
    public PrintJobResponse get(@PathVariable UUID id) {
        return jobService.get(id);
    }

    @PostMapping("/{id}/cancel")
    @SecurityRequirement(name = "serviceKey")
    public PrintJobResponse cancel(@PathVariable UUID id) {
        return jobService.cancel(id);
    }

    @PostMapping("/{id}/retry")
    @SecurityRequirement(name = "serviceKey")
    public PrintJobResponse retry(@PathVariable UUID id) {
        return jobService.retry(id);
    }

    @GetMapping(value = "/pending", params = "!printerId")
    @SecurityRequirement(name = "deviceBearer")
    public List<PrintJobResponse> legacyPending(@AuthenticationPrincipal DevicePrincipal principal,
                                          HttpServletRequest request,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        if (request.getRequestURI().startsWith("/api/print-jobs/")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PRINTER_ID_REQUIRED",
                    "printerId is required");
        }
        return jobService.claim(principal, limit);
    }

    @GetMapping(value = "/pending", params = "printerId")
    @SecurityRequirement(name = "deviceBearer")
    public List<PrintJobResponse> pendingForPrinter(@AuthenticationPrincipal DevicePrincipal principal,
                                                    @RequestParam UUID printerId,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return jobService.claimForPrinter(principal, printerId, limit);
    }

    @PatchMapping("/{id}/status")
    @SecurityRequirement(name = "deviceBearer")
    public PrintJobResponse updateStatus(@AuthenticationPrincipal DevicePrincipal principal, @PathVariable UUID id,
                                         @Valid @RequestBody UpdateJobStatusRequest request) {
        return jobService.updateStatus(principal, id, request);
    }

    @GetMapping("/history")
    @SecurityRequirement(name = "deviceBearer")
    public Page<PrintJobResponse> history(@AuthenticationPrincipal DevicePrincipal principal,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Pageable pageable = PageRequest.of(page, size);
        return jobService.history(principal, pageable);
    }

    @GetMapping("/test-payload")
    @SecurityRequirement(name = "deviceBearer")
    public ResponseEntity<byte[]> testPayload(@AuthenticationPrincipal DevicePrincipal principal,
                                              @RequestParam UUID printerId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "inline; filename=\"printer-test.bin\"")
                .body(jobService.testPayload(printerId, principal));
    }
}

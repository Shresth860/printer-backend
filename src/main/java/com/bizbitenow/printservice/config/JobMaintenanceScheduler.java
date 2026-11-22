package com.bizbitenow.printservice.config;

import com.bizbitenow.printservice.job.service.PrintJobService;
import com.bizbitenow.printservice.pairing.repository.PairingCodeRepository;
import com.bizbitenow.printservice.device.service.DeviceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class JobMaintenanceScheduler {
    private final PrintJobService jobService;
    private final PairingCodeRepository pairingCodes;
    private final DeviceService deviceService;
    @Value("${app.jobs.archive-enabled:true}")
    private boolean archiveEnabled;

    public JobMaintenanceScheduler(PrintJobService jobService, PairingCodeRepository pairingCodes,
                                   DeviceService deviceService) {
        this.jobService = jobService;
        this.pairingCodes = pairingCodes;
        this.deviceService = deviceService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryStaleJobs() {
        jobService.retryStaleJobs();
    }

    @Scheduled(fixedDelay = 30_000)
    public void failTimedOutPrintingJobs() {
        jobService.failTimedOutPrintingJobs();
    }

    @Scheduled(fixedDelayString = "${app.jobs.retry-scan-delay-ms:5000}")
    public void processDueRetries() {
        jobService.processDueRetries();
    }

    @Scheduled(fixedDelayString = "${app.printers.stale-scan-delay-ms:30000}")
    public void markStalePrintersOffline() {
        deviceService.markStalePrintersOffline();
    }

    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanExpiredPairingCodes() {
        pairingCodes.deleteByExpiresAtBefore(Instant.now());
    }

    @Scheduled(fixedDelay = 86_400_000)
    public void archiveOldJobs() {
        if (archiveEnabled) jobService.archiveOldJobs();
    }
}

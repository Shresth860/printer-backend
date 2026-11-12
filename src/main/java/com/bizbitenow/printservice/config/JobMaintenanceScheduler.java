package com.bizbitenow.printservice.config;

import com.bizbitenow.printservice.job.service.PrintJobService;
import com.bizbitenow.printservice.pairing.repository.PairingCodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
public class JobMaintenanceScheduler {
    private final PrintJobService jobService;
    private final PairingCodeRepository pairingCodes;
    @Value("${app.jobs.archive-enabled:true}")
    private boolean archiveEnabled;

    public JobMaintenanceScheduler(PrintJobService jobService, PairingCodeRepository pairingCodes) {
        this.jobService = jobService;
        this.pairingCodes = pairingCodes;
    }

    @Scheduled(fixedDelay = 30_000)
    public void retryStaleJobs() {
        jobService.retryStaleJobs();
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

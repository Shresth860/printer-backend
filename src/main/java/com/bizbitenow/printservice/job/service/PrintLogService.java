package com.bizbitenow.printservice.job.service;

import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.job.entity.PrintJob;
import com.bizbitenow.printservice.log.entity.PrintLog;
import com.bizbitenow.printservice.log.repository.PrintLogRepository;
import org.springframework.stereotype.Service;

@Service
public class PrintLogService {
    private final PrintLogRepository logs;

    public PrintLogService(PrintLogRepository logs) {
        this.logs = logs;
    }

    public void add(PrintJob job, JobStatus status, String message, AppDevice device) {
        PrintLog log = new PrintLog();
        log.setJob(job);
        log.setStatus(status);
        log.setMessage(message);
        log.setDevice(device);
        logs.save(log);
    }
}

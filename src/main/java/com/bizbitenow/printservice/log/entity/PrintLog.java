package com.bizbitenow.printservice.log.entity;

import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.bizbitenow.printservice.job.entity.PrintJob;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_log")
@Getter
@Setter
@NoArgsConstructor
public class PrintLog {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private PrintJob job;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;
    @Column(length = 1000)
    private String message;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private AppDevice device;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID();
    }
}

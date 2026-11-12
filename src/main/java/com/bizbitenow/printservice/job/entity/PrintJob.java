package com.bizbitenow.printservice.job.entity;

import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.common.JobType;
import com.bizbitenow.printservice.device.entity.AppDevice;
import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "print_job")
@Getter
@Setter
@NoArgsConstructor
public class PrintJob {
    @Id
    private UUID id;
    @Column(name = "seller_id", nullable = false, length = 160)
    private String sellerId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id")
    private AppDevice device;
    @Column(name = "order_id", nullable = false, length = 160)
    private String orderId;
    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, length = 16)
    private JobType jobType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode payloadJson;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status = JobStatus.PENDING;
    @Column(name = "retry_count", nullable = false)
    private int retryCount;
    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 5;
    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;
    @Column(name = "error_code", length = 120)
    private String errorCode;
    @Column(name = "error_message", length = 1000)
    private String errorMessage;
    @Column(name = "open_cash_drawer", nullable = false)
    private boolean openCashDrawer;
    @Column(name = "cut_paper", nullable = false)
    private boolean cutPaper = true;
    @Column(nullable = false)
    private int copies = 1;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID();
    }
}

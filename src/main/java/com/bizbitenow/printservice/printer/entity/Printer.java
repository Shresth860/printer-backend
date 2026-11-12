package com.bizbitenow.printservice.printer.entity;

import com.bizbitenow.printservice.common.ConnectionType;
import com.bizbitenow.printservice.device.entity.AppDevice;
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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "printer", uniqueConstraints = @UniqueConstraint(name = "uq_printer_device_name", columnNames = {"device_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
public class Printer {
    @Id
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_id", nullable = false)
    private AppDevice device;
    @Column(name = "seller_id", nullable = false, length = 160)
    private String sellerId;
    @Column(nullable = false, length = 160)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false, length = 16)
    private ConnectionType connectionType;
    @Column(length = 512)
    private String address;
    @Column(name = "paper_width_mm", nullable = false)
    private int paperWidthMm;
    @Column(name = "is_default", nullable = false)
    private boolean defaultPrinter;
    @Column(name = "last_status", length = 80)
    private String lastStatus;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void assignId() {
        if (id == null) id = UUID.randomUUID();
    }
}

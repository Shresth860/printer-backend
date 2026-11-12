package com.bizbitenow.printservice.device.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_device")
@Getter
@Setter
@NoArgsConstructor
public class AppDevice {
    @Id
    private UUID id;
    @Column(name = "seller_id", nullable = false, length = 160)
    private String sellerId;
    @Column(name = "device_name", nullable = false, length = 160)
    private String deviceName;
    @Column(name = "android_version", nullable = false, length = 80)
    private String androidVersion;
    @Column(name = "app_version", nullable = false, length = 80)
    private String appVersion;
    @Column(name = "fcm_token", length = 4096)
    private String fcmToken;
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;
    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;
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

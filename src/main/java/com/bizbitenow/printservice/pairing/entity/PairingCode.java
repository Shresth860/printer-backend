package com.bizbitenow.printservice.pairing.entity;

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
@Table(name = "pairing_code")
@Getter
@Setter
@NoArgsConstructor
public class PairingCode {
    @Id
    private UUID id;
    @Column(name = "seller_id", nullable = false, length = 160)
    private String sellerId;
    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "used_at")
    private Instant usedAt;
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

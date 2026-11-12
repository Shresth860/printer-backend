package com.bizbitenow.printservice.apk.entity;

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
@Table(name = "app_release")
@Getter
@Setter
@NoArgsConstructor
public class AppRelease {
    @Id
    private UUID id;
    @Column(name = "version_code", nullable = false, unique = true)
    private int versionCode;
    @Column(name = "version_name", nullable = false, length = 80)
    private String versionName;
    @Column(name = "apk_url", nullable = false, length = 2048)
    private String apkUrl;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Column(nullable = false)
    private boolean mandatory;
    @Column(name = "release_notes", length = 4000)
    private String releaseNotes;
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

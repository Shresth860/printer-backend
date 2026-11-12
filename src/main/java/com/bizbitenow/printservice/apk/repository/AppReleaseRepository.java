package com.bizbitenow.printservice.apk.repository;

import com.bizbitenow.printservice.apk.entity.AppRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AppReleaseRepository extends JpaRepository<AppRelease, UUID> {
    Optional<AppRelease> findFirstByOrderByVersionCodeDesc();
}

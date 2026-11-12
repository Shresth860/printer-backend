package com.bizbitenow.printservice.device.repository;

import com.bizbitenow.printservice.device.entity.AppDevice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppDeviceRepository extends JpaRepository<AppDevice, UUID> {
    List<AppDevice> findBySellerIdAndActiveTrue(String sellerId);
    List<AppDevice> findBySellerIdOrderByCreatedAtDesc(String sellerId);
    Optional<AppDevice> findByRefreshTokenHashAndActiveTrue(String refreshTokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AppDevice d where d.refreshTokenHash = :hash and d.active = true")
    Optional<AppDevice> findRefreshTokenForUpdate(@Param("hash") String hash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AppDevice d where d.id = :id and d.active = true")
    Optional<AppDevice> findActiveForUpdate(@Param("id") UUID id);
}

package com.bizbitenow.printservice.pairing.repository;

import com.bizbitenow.printservice.pairing.entity.PairingCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {
    boolean existsByCode(String code);
    long deleteByExpiresAtBefore(Instant before);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingCode p where p.code = :code and p.usedAt is null and p.expiresAt > :now")
    Optional<PairingCode> findUsableForUpdate(@Param("code") String code, @Param("now") Instant now);
}

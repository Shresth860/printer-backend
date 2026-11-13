package com.bizbitenow.printservice.pairing.repository;

import com.bizbitenow.printservice.pairing.entity.PairingCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PairingCodeRepository extends JpaRepository<PairingCode, UUID> {
    @Modifying
    @Query(value = """
            insert into pairing_code (id, seller_id, code_hash, expires_at)
            values (:id, :sellerId, :codeHash, :expiresAt)
            on conflict (code_hash) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("sellerId") String sellerId,
                       @Param("codeHash") String codeHash, @Param("expiresAt") Instant expiresAt);
    long deleteByExpiresAtBefore(Instant before);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PairingCode p where p.codeHash = :codeHash and p.usedAt is null and p.expiresAt > :now")
    Optional<PairingCode> findUsableForUpdate(@Param("codeHash") String codeHash, @Param("now") Instant now);
}

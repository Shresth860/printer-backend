package com.bizbitenow.printservice.job.repository;

import com.bizbitenow.printservice.common.JobStatus;
import com.bizbitenow.printservice.job.entity.PrintJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrintJobRepository extends JpaRepository<PrintJob, UUID> {
    Optional<PrintJob> findBySellerIdAndIdempotencyKey(String sellerId, String idempotencyKey);
    Page<PrintJob> findBySellerId(String sellerId, Pageable pageable);
    Page<PrintJob> findBySellerIdAndStatus(String sellerId, JobStatus status, Pageable pageable);
    Page<PrintJob> findByDeviceIdAndSellerId(UUID deviceId, String sellerId, Pageable pageable);

    @Query(value = """
            SELECT * FROM print_job
            WHERE seller_id = :sellerId AND status = 'PENDING'
              AND (device_id IS NULL OR device_id = :deviceId)
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PrintJob> claimable(@Param("sellerId") String sellerId, @Param("deviceId") UUID deviceId,
                             @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PrintJob j where j.status = 'DISPATCHED' and j.dispatchedAt < :before")
    List<PrintJob> findStaleDispatchedForUpdate(@Param("before") Instant before);
}

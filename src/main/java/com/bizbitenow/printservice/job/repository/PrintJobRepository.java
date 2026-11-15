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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PrintJob j where j.id = :id")
    Optional<PrintJob> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select j from PrintJob j
            left join j.device d
            left join j.printer p
            left join p.device pd
            where j.sellerId = :sellerId
              and (d.id = :deviceId or pd.id = :deviceId)
            """)
    Page<PrintJob> findDeviceHistory(@Param("deviceId") UUID deviceId, @Param("sellerId") String sellerId,
                                    Pageable pageable);

    @Query(value = """
            SELECT * FROM print_job
            WHERE seller_id = :sellerId AND status = 'PENDING'
              AND (device_id IS NULL OR device_id = :deviceId)
              AND ((:printerId IS NULL AND (printer_id IS NULL OR printer_id IN
                    (SELECT id FROM printer WHERE device_id = :deviceId)))
                   OR (:printerId IS NOT NULL AND (printer_id IS NULL OR printer_id = :printerId)))
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PrintJob> claimable(@Param("sellerId") String sellerId, @Param("deviceId") UUID deviceId,
                             @Param("printerId") UUID printerId, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PrintJob j where j.status in ('DISPATCHED', 'SENT_TO_DEVICE') and j.dispatchedAt < :before")
    List<PrintJob> findStaleDispatchedForUpdate(@Param("before") Instant before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from PrintJob j where j.status = 'PRINTING' and j.printingStartedAt < :before")
    List<PrintJob> findStalePrintingForUpdate(@Param("before") Instant before);
}

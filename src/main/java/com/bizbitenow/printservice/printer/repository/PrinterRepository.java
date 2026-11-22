package com.bizbitenow.printservice.printer.repository;

import com.bizbitenow.printservice.printer.entity.Printer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {
    Optional<Printer> findByDeviceIdAndName(UUID deviceId, String name);
    List<Printer> findByDeviceIdOrderByName(UUID deviceId);
    List<Printer> findByDeviceId(UUID deviceId);
    List<Printer> findBySellerIdOrderByName(String sellerId);

    @Modifying
    @Query("update Printer p set p.lastStatus = 'OFFLINE' where p.lastSeenAt is not null and p.lastSeenAt < :threshold and p.lastStatus <> 'OFFLINE'")
    int markStaleOffline(@Param("threshold") Instant threshold);
}

package com.bizbitenow.printservice.printer.repository;

import com.bizbitenow.printservice.printer.entity.Printer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PrinterRepository extends JpaRepository<Printer, UUID> {
    Optional<Printer> findByDeviceIdAndName(UUID deviceId, String name);
    List<Printer> findByDeviceIdOrderByName(UUID deviceId);
    List<Printer> findByDeviceId(UUID deviceId);
}

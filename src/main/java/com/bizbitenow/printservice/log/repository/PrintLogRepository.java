package com.bizbitenow.printservice.log.repository;

import com.bizbitenow.printservice.log.entity.PrintLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PrintLogRepository extends JpaRepository<PrintLog, UUID> {
}

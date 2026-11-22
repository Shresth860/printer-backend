package com.bizbitenow.printservice.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
class LegacyJobStatusMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("migration_test").withUsername("test").withPassword("test");

    @Test
    void migratesLegacyJobStatusesWithoutTruncation() throws Exception {
        Flyway beforeLifecycle = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("5"))
                .load();
        beforeLifecycle.migrate();

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("PENDING", "QUEUED");
        expected.put("DISPATCHED", "SENT_TO_PRINTER");
        expected.put("SENT_TO_DEVICE", "SENT_TO_PRINTER");
        expected.put("PRINTING", "SENT_TO_PRINTER");
        expected.put("SUCCESS", "PRINTED_CONFIRMED");
        expected.put("FAILED", "FAILED");
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var insert = connection.prepareStatement("""
                     INSERT INTO print_job (id, seller_id, order_id, job_type, payload_json, status, idempotency_key)
                     VALUES (?, ?, ?, 'RECEIPT', '{}'::jsonb, ?, ?)
                     """)) {
            for (Map.Entry<String, String> legacy : expected.entrySet()) {
                insert.setObject(1, UUID.randomUUID());
                insert.setString(2, "migration-seller");
                insert.setString(3, "legacy-" + legacy.getKey());
                insert.setString(4, legacy.getKey());
                insert.setString(5, "legacy-key-" + legacy.getKey());
                insert.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT order_id, status FROM print_job WHERE seller_id = 'migration-seller'")) {
            Map<String, String> actual = new LinkedHashMap<>();
            while (result.next()) actual.put(result.getString("order_id").substring("legacy-".length()), result.getString("status"));
            assertEquals(expected, actual);
        }
    }
}

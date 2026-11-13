package com.bizbitenow.printservice;

import com.bizbitenow.printservice.common.JobType;
import com.bizbitenow.printservice.device.dto.PairDeviceRequest;
import com.bizbitenow.printservice.device.dto.TokenResponse;
import com.bizbitenow.printservice.job.dto.CreatePrintJobRequest;
import com.bizbitenow.printservice.job.dto.UpdateJobStatusRequest;
import com.bizbitenow.printservice.job.service.PrintJobService;
import com.bizbitenow.printservice.pairing.service.PairingService;
import com.bizbitenow.printservice.pairing.service.PairingCodeHasher;
import com.bizbitenow.printservice.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PrintServiceIntegrationTest {
    private static final String SERVICE_KEY = "integration-service-key";
    private static final String JWT_SECRET = "integration-test-secret-material-over-32-bytes-long";
    private static final String SELLER_ORIGIN = "https://seller.example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("print_service").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.security.jwt-secret", () -> JWT_SECRET);
        registry.add("app.security.service-api-key", () -> SERVICE_KEY);
        registry.add("app.cors.allowed-origins", () -> SELLER_ORIGIN);
    }

    @Autowired PairingService pairingService;
    @Autowired PairingCodeHasher pairingCodeHasher;
    @Autowired PrintJobService jobService;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE print_log, print_job, printer, pairing_code, app_device, app_release, print_job_archive CASCADE");
    }

    @Test
    void pairCreateClaimAndCompletePrintJob() throws Exception {
        TokenResponse tokens = pair("seller-flow", "Flow printer");
        JsonNode payload = validPayload();
        CreatePrintJobRequest request = new CreatePrintJobRequest("seller-flow", "order-1", JobType.RECEIPT,
                payload, false, true, 1, "order-1-receipt");

        var created = jobService.create(request);
        var duplicate = jobService.create(request);
        assertTrue(created.created());
        assertFalse(duplicate.created());
        assertEquals(created.job().id(), duplicate.job().id());

        var claimed = jobService.claim(new com.bizbitenow.printservice.security.DevicePrincipal(
                tokens.deviceId(), tokens.sellerId()), 20);
        assertEquals(1, claimed.size());
        assertEquals("DISPATCHED", claimed.getFirst().status().name());
        UUID jobId = claimed.getFirst().id();
        var principal = new com.bizbitenow.printservice.security.DevicePrincipal(tokens.deviceId(), tokens.sellerId());
        jobService.updateStatus(principal, jobId, new UpdateJobStatusRequest(
                com.bizbitenow.printservice.common.JobStatus.PRINTING, null, null));
        var success = jobService.updateStatus(principal, jobId, new UpdateJobStatusRequest(
                com.bizbitenow.printservice.common.JobStatus.SUCCESS, null, null));
        assertEquals("SUCCESS", success.status().name());
    }

    @Test
    void concurrentDevicesNeverClaimTheSameJob() throws Exception {
        TokenResponse first = pair("seller-race", "Printer one");
        TokenResponse second = pair("seller-race", "Printer two");
        for (int i = 0; i < 40; i++) {
            jobService.create(new CreatePrintJobRequest("seller-race", "order-" + i, JobType.TEST,
                    validPayload(), false, true, 1, "race-" + i));
        }
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstClaim = executor.submit(() -> {
                start.await();
                return jobService.claim(new com.bizbitenow.printservice.security.DevicePrincipal(
                        first.deviceId(), first.sellerId()), 20);
            });
            var secondClaim = executor.submit(() -> {
                start.await();
                return jobService.claim(new com.bizbitenow.printservice.security.DevicePrincipal(
                        second.deviceId(), second.sellerId()), 20);
            });
            start.countDown();
            var firstJobs = firstClaim.get();
            var secondJobs = secondClaim.get();
            List<UUID> allIds = new ArrayList<>();
            firstJobs.forEach(job -> allIds.add(job.id()));
            secondJobs.forEach(job -> allIds.add(job.id()));
            assertEquals(40, allIds.size());
            assertEquals(40, allIds.stream().distinct().count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pairingCodesAreHashedOneTimeAndExpire() throws Exception {
        var pairingCode = pairingService.createCode("seller-pairing-security");
        String storedHash = jdbc.queryForObject("select code_hash from pairing_code where seller_id = ?",
                String.class, "seller-pairing-security");
        assertEquals(pairingCodeHasher.hashCode(pairingCode.code()), storedHash);
        assertFalse(jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.columns
                    where table_name = 'pairing_code' and column_name = 'code'
                )
                """, Boolean.class));

        String request = """
                {"code":"%s","deviceName":"Secure printer","androidVersion":"14","appVersion":"1.0.0"}
                """.formatted(pairingCode.code());
        mockMvc.perform(post("/api/v1/auth/pair").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        String reuseError = mockMvc.perform(post("/api/v1/auth/pair")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INVALID_PAIRING_CODE")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(reuseError.contains(pairingCode.code()));

        var expiredCode = pairingService.createCode("seller-expired-code");
        jdbc.update("update pairing_code set expires_at = now() - interval '1 second' where code_hash = ?",
                pairingCodeHasher.hashCode(expiredCode.code()));
        String expiredRequest = """
                {"code":"%s","deviceName":"Expired printer","androidVersion":"14","appVersion":"1.0.0"}
                """.formatted(expiredCode.code());
        String expiryError = mockMvc.perform(post("/api/v1/auth/pair")
                        .contentType(MediaType.APPLICATION_JSON).content(expiredRequest))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INVALID_PAIRING_CODE")))
                .andReturn().getResponse().getContentAsString();
        assertFalse(expiryError.contains(expiredCode.code()));
    }

    @Test
    void concurrentPairingCodeGenerationRemainsUnique() throws Exception {
        int count = 12;
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<String>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                results.add(executor.submit(() -> {
                    start.await();
                    return pairingService.createCode("seller-concurrent-" + index).code();
                }));
            }
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (var result : results) codes.add(result.get());

            assertEquals(count, codes.stream().distinct().count());
            assertEquals(count, jdbc.queryForObject("select count(distinct code_hash) from pairing_code", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void authenticatedDeviceCanOnlyRevokeItself() throws Exception {
        TokenResponse self = pair("seller-self-revoke", "Self device");
        TokenResponse other = pair("seller-self-revoke", "Other device");
        String accessToken = jwtService.issueAccessToken(self.deviceId(), self.sellerId());

        mockMvc.perform(delete("/api/v1/devices/me"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/devices/me").header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/devices/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertFalse(jdbc.queryForObject("select is_active from app_device where id = ?",
                Boolean.class, self.deviceId()));
        assertTrue(jdbc.queryForObject("select is_active from app_device where id = ?",
                Boolean.class, other.deviceId()));
        assertNull(jdbc.queryForObject("select refresh_token_hash from app_device where id = ?",
                String.class, self.deviceId()));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + self.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/devices/me/heartbeat")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appVersion\":\"1.0.0\",\"printers\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void latestReleaseAllowsCorsOnlyFromConfiguredFrontendOrigin() throws Exception {
        jdbc.update("""
                insert into app_release(version_code, version_name, apk_url, sha256)
                values (1, '1.0.0', 'https://downloads.example.com/app.apk', repeat('a', 64))
                """);

        mockMvc.perform(get("/api/v1/app/latest-release").header("Origin", SELLER_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", SELLER_ORIGIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mockMvc.perform(get("/api/v1/app/latest-release").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void controllerReturnsExpectedAuthenticationAndBusinessErrors() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/pending"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));

        mockMvc.perform(get("/api/v1/jobs/{id}", UUID.randomUUID())
                        .header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("JOB_NOT_FOUND")));

        mockMvc.perform(post("/api/v1/jobs")
                        .header("X-Service-Key", SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson("bad-payload", "{\"paperWidthMm\":40,\"blocks\":[]}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("INVALID_PAYLOAD")));

        String jobId = createJobOverHttp("cancel-test", validPayload().toString());
        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", jobId)
                        .header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/jobs/{id}/cancel", jobId)
                        .header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_JOB_STATE")));

        TokenResponse owner = pair("seller-owner", "Owner device");
        TokenResponse other = pair("seller-owner", "Other device");
        var created = jobService.create(new CreatePrintJobRequest("seller-owner", "owned-order", JobType.TEST,
                validPayload(), false, true, 1, "owned-job"));
        jobService.claim(new com.bizbitenow.printservice.security.DevicePrincipal(owner.deviceId(), owner.sellerId()), 1);
        mockMvc.perform(patch("/api/v1/jobs/{id}/status", created.job().id())
                        .header("Authorization", "Bearer " + jwtService.issueAccessToken(other.deviceId(), other.sellerId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PRINTING\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("JOB_NOT_OWNED")));
    }

    @Test
    void deviceCanClaimPendingJobsAndUpdateOwnedStatusOverHttp() throws Exception {
        TokenResponse device = pair("seller-device-http", "HTTP printer");
        var created = jobService.create(new CreatePrintJobRequest("seller-device-http", "device-order",
                JobType.TEST, validPayload(), false, true, 1, "device-http-job"));
        String accessToken = jwtService.issueAccessToken(device.deviceId(), device.sellerId());

        String claimedResponse = mockMvc.perform(get("/api/v1/jobs/pending")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(created.job().id().toString())))
                .andExpect(jsonPath("$[0].status", is("DISPATCHED")))
                .andReturn().getResponse().getContentAsString();
        assertEquals(created.job().id().toString(), objectMapper.readTree(claimedResponse).get(0).get("id").asText());

        mockMvc.perform(patch("/api/v1/jobs/{id}/status", created.job().id())
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PRINTING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PRINTING")));
    }

    @Test
    void deviceAndServiceCredentialsCannotAccessEachOthersEndpoints() throws Exception {
        TokenResponse device = pair("seller-role-boundary", "Role boundary printer");
        String accessToken = jwtService.issueAccessToken(device.deviceId(), device.sellerId());

        mockMvc.perform(get("/api/v1/jobs")
                        .param("sellerId", device.sellerId())
                        .header("Authorization", "Bearer " + accessToken)
                        .header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));

        mockMvc.perform(get("/api/v1/jobs/pending")
                        .header("X-Service-Key", SERVICE_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("FORBIDDEN")));
    }

    @Test
    void openApiDocumentsServiceAndDeviceSecuritySchemes() throws Exception {
        JsonNode openApi = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode schemes = openApi.path("components").path("securitySchemes");

        assertEquals("http", schemes.path("deviceBearer").path("type").asText());
        assertEquals("apiKey", schemes.path("serviceKey").path("type").asText());
        assertEquals("header", schemes.path("serviceKey").path("in").asText());
        assertEquals("X-Service-Key", schemes.path("serviceKey").path("name").asText());

        assertSecurityRequirement(openApi, "/api/v1/pairing-codes", "post", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/jobs", "post", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/jobs", "get", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/jobs/{id}", "get", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/jobs/{id}/cancel", "post", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/jobs/{id}/retry", "post", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/sellers/{sellerId}/devices", "get", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/devices/{id}", "delete", "serviceKey");
        assertSecurityRequirement(openApi, "/api/v1/devices/me", "delete", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/releases", "post", "serviceKey");

        assertSecurityRequirement(openApi, "/api/v1/devices/me/heartbeat", "post", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/devices/me/fcm-token", "put", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/devices/me/printers", "put", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/jobs/pending", "get", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/jobs/{id}/status", "patch", "deviceBearer");
        assertSecurityRequirement(openApi, "/api/v1/jobs/history", "get", "deviceBearer");
    }

    private void assertSecurityRequirement(JsonNode openApi, String path, String method, String scheme) {
        JsonNode requirements = openApi.path("paths").path(path).path(method).path("security");
        for (JsonNode requirement : requirements) {
            if (requirement.has(scheme)) {
                return;
            }
        }
        fail("Missing " + scheme + " security requirement for " + method.toUpperCase() + " " + path);
    }

    private TokenResponse pair(String seller, String name) {
        var code = pairingService.createCode(seller);
        return pairingService.pair(new PairDeviceRequest(code.code(), name, "14", "1.0.0", null));
    }

    private JsonNode validPayload() throws Exception {
        return objectMapper.readTree("""
                {"paperWidthMm":58,"blocks":[{"type":"TEXT","text":"Receipt","align":"CENTER","bold":true,"size":"NORMAL"}]}
                """);
    }

    private String createJobOverHttp(String key, String payload) throws Exception {
        String response = mockMvc.perform(post("/api/v1/jobs")
                        .header("X-Service-Key", SERVICE_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJobJson(key, payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asText();
    }

    private String createJobJson(String key, String payload) {
        return "{\"sellerId\":\"seller-http\",\"orderId\":\"order-http\",\"jobType\":\"RECEIPT\","
                + "\"payload\":" + payload + ",\"openCashDrawer\":false,\"cutPaper\":true,"
                + "\"copies\":1,\"idempotencyKey\":\"" + key + "\"}";
    }
}

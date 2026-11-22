# BizBiteNow Print Service

Spring Boot 3.3 / Java 21 backend for pairing Android printer devices, dispatching print jobs, and reporting printer state. PostgreSQL schema changes are managed by Flyway.

## Environment variables

| Variable | Required | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Production | Set to `prod` to use required environment-only DB and secret settings. |
| `DATABASE_URL` | Yes | JDBC URL, for example `jdbc:postgresql://db.example:5432/print_service`. |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | Yes | PostgreSQL credentials. |
| `JWT_SECRET` | Yes | Random secret with at least 32 bytes for device JWT signing and pairing-code hashing. |
| `SERVICE_API_KEY` | Yes | Secret key for seller/service routes. |
| `FIREBASE_CREDENTIALS` | No | Mounted Firebase service-account JSON file path; without it, device polling still works but push delivery is unavailable. |
| `PORT` | No | HTTP listen port; defaults to `8080`. |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated exact trusted browser origins; wildcard is rejected. |
| `JOB_MAX_RETRIES` | No | Maximum automated retries; defaults to `3`. |
| `JOB_RETRY_DELAY_SECONDS` | No | Delay before retry dispatch; defaults to `30`. |
| `JOB_RETRY_SCAN_DELAY_MS` | No | Retry scheduler interval; defaults to `5000`. |
| `PRINTER_STALE_SECONDS` | No | Heartbeat age after which a printer is considered stale; defaults to `120`. |
| `PRINTER_STALE_SCAN_DELAY_MS` | No | Printer stale-state scheduler interval; defaults to `30000`. |
| `JOB_ARCHIVE_ENABLED` | No | Enables 90-day archival; defaults to `true`. |
| `DB_CONNECTION_TIMEOUT_MS`, `DB_VALIDATION_TIMEOUT_MS`, `DB_INITIALIZATION_FAIL_TIMEOUT_MS` | No | Production Hikari timeouts in milliseconds. |
| `SERVER_CONNECTION_TIMEOUT` | No | Production server connection timeout; defaults to `20s`. |

## Run and deploy

For local Docker Compose, copy `.env.example` to `.env`, set strong `DATABASE_PASSWORD`, `JWT_SECRET`, and `SERVICE_API_KEY`, then run:

```powershell
docker compose up -d --build
```

For a hosted deployment, create a PostgreSQL database, build with `mvn clean package`, deploy `target/print-service-1.0.0.jar` or the Docker image, set `SPRING_PROFILES_ACTIVE=prod`, and configure the environment variables above through the host's secret/environment settings. Set `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, `JWT_SECRET`, and `SERVICE_API_KEY`; mount the Firebase JSON file and set `FIREBASE_CREDENTIALS` if push notifications are needed. Terminate HTTPS at the platform proxy and expose the configured port. Flyway applies migrations at startup; Hibernate validates the resulting schema.

Run the packaged app locally with:

```powershell
$env:SPRING_PROFILES_ACTIVE = "prod"
java -jar target/print-service-1.0.0.jar
```

Health check: `GET /actuator/health`. API docs: `/swagger-ui`. The Android `BASE_URL` is the origin only, with scheme and host (and optional port), e.g. `https://printer-api.example.com`; do not append `/api/v1`.

## Authentication and API contract

Device routes use `Authorization: Bearer <accessToken>`. Seller/service routes use `X-Service-Key: <SERVICE_API_KEY>`. Pair and refresh are public. JSON errors use `{ "timestamp": "...", "status": 400, "code": "...", "message": "...", "path": "..." }`; validation errors retain HTTP 422. Typical errors: `400` malformed request/evidence, `401` missing/invalid/expired device token, `403` missing/invalid service key or ownership failure, `404` unknown resource, `409` invalid state or reused sync UUID, `422` validation/business payload errors, `429` pairing rate limit, `500` unexpected server error.

| Method and URL | Auth | Request | Response |
|---|---|---|---|
| `POST /api/v1/auth/pair` | Public | `{ "code":"12345678", "deviceName":"Register", "androidVersion":"14", "appVersion":"1.0", "fcmToken":"..." }` (`fcmToken` optional) | `TokenResponse`: `accessToken`, `refreshToken`, `deviceId`, `sellerId`, `accessTokenExpiresAt`. Repeating the same pair request for the paired device is idempotent. |
| `POST /api/v1/auth/refresh` | Public | `{ "refreshToken":"..." }` | Rotated `TokenResponse` as above. |
| `POST /api/v1/pairing-codes` | Service key | `{ "sellerId":"seller-123" }` | Pairing code response; expires after ten minutes. |
| `POST /api/v1/devices/me/heartbeat` | Device bearer | `{ "appVersion":"1.0", "printers":[{"name":"USB Printer","status":"ONLINE"}] }` | `200`, empty body. Updates device/printer heartbeat time. Status values: `ONLINE`, `OFFLINE`, `PAPER_OUT`, `COVER_OPEN`, `ERROR`; `CONNECTED`/`DISCONNECTED` remain accepted aliases. |
| `PATCH /api/v1/devices/me/printers/{printerId}/status` | Device bearer | `{ "status":"ONLINE" }` | `200`, empty body; updates status and `lastSeenAt`. |
| `PUT /api/v1/devices/me/printers` | Device bearer | `{ "printers":[{"name":"USB Printer","connectionType":"USB","address":"...","paperWidthMm":80,"defaultPrinter":true,"status":"ONLINE"}] }` | List of printer records including `id`, status, `lastSeenAt`. Upsert is by device/name. |
| `PUT /api/v1/devices/me/fcm-token` | Device bearer | `{ "fcmToken":"..." }` | `200`, empty body. |
| `DELETE /api/v1/devices/me` | Device bearer | No body | `200`, empty body; revokes the calling device only. |
| `GET /api/v1/jobs/pending?limit=20[&printerId={uuid}]` | Device bearer | No body | Array of claimed jobs. Legacy `status` remains available (`SENT_TO_DEVICE`/`DISPATCHED` as applicable); `lifecycleStatus` reports canonical `SENT_TO_PRINTER`. |
| `PATCH /api/v1/jobs/{id}/status` | Device bearer | `{ "status":"PRINTING" }`, `{ "status":"SUCCESS", "connected":true, "bytesWritten":128, "flushOk":true }`, or `{ "status":"FAILED", "errorCode":"PAPER_OUT", "errorMessage":"Paper empty" }` | Updated job response. Success is recorded as canonical `PRINTED_CONFIRMED` only with all three evidence fields. Legacy `status` still returns `SUCCESS`; `lifecycleStatus` exposes canonical state. |
| `POST /api/v1/jobs/sync-results` | Device bearer | `{ "results":[{"clientId":"uuid","jobId":"uuid","status":"PRINTED_CONFIRMED","clientTimestamp":"2026-01-01T12:00:00Z","connected":true,"bytesWritten":128,"flushOk":true}] }` (max 100) | `{ "results":[{"clientId":"uuid","jobId":"uuid","lifecycleStatus":"PRINTED_CONFIRMED","applied":true,"duplicate":false}] }`. Client UUIDs deduplicate replays; older client timestamps do not overwrite newer state. |
| `GET /api/v1/jobs/history?page=0&size=20` | Device bearer | No body | Paginated job response, preserving legacy `status` and adding `lifecycleStatus` and `nextRetryAt`. |
| `GET /api/v1/jobs/test-payload?printerId={uuid}` | Device bearer | No body | Raw `application/octet-stream` ESC/POS test payload. |
| `GET /api/v1/sellers/{sellerId}/printers` | Service key | No body | List of actual printer statuses and `lastSeenAt`; stale printers are reported as `OFFLINE`. |
| `GET /api/v1/sellers/{sellerId}/devices` | Service key | No body | Seller's registered device list and last-seen/online fields. |
| `POST /api/v1/jobs` (legacy alias `/api/print-jobs`) | Service key | `{ "sellerId":"seller-123", "orderId":"order-1", "jobType":"RECEIPT", "payload":{}, "openCashDrawer":false, "cutPaper":true, "copies":1, "idempotencyKey":"...", "printerId":"uuid" }` | `201` new or `200` idempotent replay; job response. |
| `GET /api/v1/jobs?sellerId=...&status=...&page=0&size=20`, `GET /api/v1/jobs/{id}` | Service key | No body | Page/job response; legacy status aliases are accepted as filters. |
| `POST /api/v1/jobs/{id}/cancel`, `POST /api/v1/jobs/{id}/retry` | Service key | No body | Updated job response; invalid state or exhausted retries returns `409`. |
| `GET /api/v1/app/latest-release` | Public | No body | `{ "versionCode", "versionName", "apkUrl", "sha256", "mandatory", "releaseNotes", "createdAt" }` or `404` when none exists. |
| `POST /api/v1/releases` | Service key | `{ "versionCode":12, "versionName":"1.2.0", "apkUrl":"https://.../app.apk", "sha256":"64-hex-digest", "mandatory":false, "releaseNotes":"..." }` | `201` `{ "versionCode", "versionName", "apkUrl", "sha256", "mandatory", "releaseNotes", "createdAt" }`. |
| `GET /actuator/health` | Public | No body | Actuator health JSON. |

### Compatibility notes

- Existing `/api/v1/jobs` and `/api/print-jobs` routes remain. Existing `status` response property retains legacy values; `lifecycleStatus` is additive and canonical.
- Canonical persisted job statuses are `QUEUED`, `SENT_TO_PRINTER`, `PRINTED_CONFIRMED`, `FAILED`, and `CANCELLED`. Migration V6 converts old database values while keeping API aliases. Legacy `PRINTING` callbacks remain accepted while in `SENT_TO_PRINTER`.
- Job responses add `lifecycleStatus` and `nextRetryAt`; status changes to success now require connection/write/flush evidence, and failure callbacks require a reason. Automated retries may be delayed up to the configured limit.
- Printer statuses add `ONLINE`, `OFFLINE`, `PAPER_OUT`, `COVER_OPEN`, and `ERROR`; old `CONNECTED`/`DISCONNECTED` inputs are normalized.
- Offline sync and seller printer status are new endpoints. Reusing an offline-sync `clientId` for another job returns `409`.
- Error response uses `code` (not `error`) and validation remains `422` for compatibility.

Job responses contain `id`, `sellerId`, `deviceId`, `orderId`, `jobType`, `payload`, legacy `status`, canonical `lifecycleStatus`, `retryCount`, `maxRetries`, `errorCode`, `errorMessage`, `openCashDrawer`, `cutPaper`, `copies`, `createdAt`, `updatedAt`, `dispatchedAt`, `printedAt`, `completedAt`, `printerId`, `payloadBytesBase64`, and `nextRetryAt`. Paginated history/list endpoints return Spring's page object with `content` and pagination metadata.

## Printing behavior and tests

Jobs move through queued, dispatched, and confirmed/failed/cancelled states. A device-reported failure or timeout is retried up to `JOB_MAX_RETRIES`, with the reason retained if retries are exhausted. The backend records a print as confirmed only after a device reports a successful printer response with `connected=true`, positive `bytesWritten`, and `flushOk=true`; actual paper output still requires validation with a real printer.

Run unit and Testcontainers integration tests with `mvn clean test` (Docker must be available for PostgreSQL integration tests). Package with `mvn clean package`.

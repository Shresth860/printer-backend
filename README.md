# BizBiteNow Print Service

Spring Boot 3.3 / Java 21 backend for pairing Android printer devices, dispatching print jobs, and recording printer status.

## Requirements

- Java 21
- Maven 3.9+
- Docker Compose (recommended)

## Configure and run with Docker Compose

Copy `.env.example` to `.env` and replace the placeholder values for `DATABASE_PASSWORD`, `SERVICE_API_KEY`, and `JWT_SECRET`. The JWT secret must contain at least 256 bits of random material. For push notifications, set `FIREBASE_CREDENTIALS` to the mounted path of a Firebase service-account JSON file; do not commit that file. Without Firebase credentials, jobs remain available through device polling.

Start the application and PostgreSQL:

```shell
docker compose up -d --build
```

Stop the application:

```shell
docker compose down
```

The `.env` file and Firebase service-account credentials are excluded from Git and Docker build contexts.

## Run locally

Start PostgreSQL separately, export the variables from `.env`, then run:

```shell
mvn spring-boot:run
```

Flyway creates and evolves the schema. Hibernate runs with `ddl-auto=validate`. The default local database connection is `jdbc:postgresql://localhost:5432/print_service`.

## API

- OpenAPI UI: `http://localhost:8080/swagger-ui`
- Health: `http://localhost:8080/actuator/health`
- API base: `/api/v1`
- Panel/backend endpoints require `X-Service-Key`.
- Device endpoints require `Authorization: Bearer <device-access-token>`.

See `postman/Print-Service.postman_collection.json` for a starter collection. Pairing codes expire after ten minutes. Device access tokens last 15 minutes; refresh tokens last 30 days and rotate on each successful refresh.

## Tests

Run `mvn clean verify`. Integration tests use Testcontainers and require Docker to be available.

## Deployment notes

Deploy behind TLS termination, use a managed PostgreSQL instance, mount Firebase credentials read-only, and inject secrets through the platform's secret manager. Restrict panel CORS origins to trusted origins. Configure database backups and monitor application/Actuator health. API keys and JWT signing secrets must be rotated through your deployment process.

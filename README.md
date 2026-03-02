# Boot4 Reference

Spring Boot 4 / Java 25 reference backend.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Framework | Spring Boot 4.0.3 |
| Language | Java 25 |
| Database | PostgreSQL 16 + Flyway migrations |
| Messaging | Apache Kafka (KRaft mode, native image) |
| Observability | OpenTelemetry → Grafana (Tempo, Loki, Prometheus) |
| Testing | JUnit 5 + Mockito + Testcontainers + datasource-proxy |
| Build | Maven, multi-stage Docker, AOT/native profiles |

## Prerequisites

- Java 25 (Eclipse Temurin recommended)
- Maven 3.9+
- Docker & Docker Compose, or Podman (for infrastructure stack)
- PostgreSQL 16 (or use Testcontainers — tests manage their own)

## Quick Start

```bash
# Clone and build (runs all tests — unit + integration with Testcontainers)
git clone <repo-url> && cd boot4-reference
mvn verify

# Start infrastructure (Postgres, Kafka, Grafana, Prometheus, Tempo, Loki)
cd infra && ./start.sh

# Run the application
cd .. && mvn spring-boot:run
```

The API is available at `http://localhost:8080`. Management endpoints (health, metrics, Prometheus) at `http://localhost:8081`.

## Project Structure

```
src/main/java/com/example/boot4ref/
├── common/          # Base entity, exceptions, ExceptionTranslator, Faker fixtures
├── config/          # ApplicationProperties, RetryConfiguration
├── product/         # Product domain: entity, CRUD, lifecycle, filtering, pagination
├── order/           # Order domain: entity, lifecycle, stock, idempotency, locking
└── outbox/          # Transactional outbox: CloudEvents, SKIP LOCKED poller, Kafka

infra/               # Docker/Podman Compose stack, Grafana dashboards, OTel config
docs/                # HTML documentation (spec, setup, patterns, testing)
```

## Key Patterns

- **RFC 9457 Problem Details** — structured error responses with trace IDs
- **Keyset pagination** — cursor-based, no OFFSET performance degradation
- **Transactional outbox** — ACID-safe event publishing with SKIP LOCKED polling
- **Sealed event hierarchy** — compile-time exhaustiveness for domain events
- **Pessimistic locking** — deadlock-free stock management (sorted PK acquisition)
- **Idempotency keys** — at-most-once order creation
- **Partial updates** — PATCH applies non-null fields only

## Documentation

| Document | Description |
|----------|-------------|
| [API Specification](docs/spec.html) | Complete REST API reference |
| [Setup Guide](docs/setup.html) | Prerequisites, IDE setup, Docker stack |
| [Request Lifecycle](docs/request-lifecycle.html) | HTTP request traced through every layer |
| [Error Handling](docs/error-handling.html) | RFC 9457, exception hierarchy, trace IDs |
| [Testing Strategy](docs/testing-strategy.html) | Testing pyramid, test types, patterns |
| [Outbox Pattern](docs/outbox-pattern.html) | Dual-write problem, ACID solution, failure modes |
| [Observability](docs/observability.html) | Logs, traces, metrics, Grafana dashboards |

## Docker

```bash
# Build application image
docker build -t boot4-reference .

# Full stack (infra + app)
# Uncomment the 'app' service in infra/docker-compose.yml, then:
cd infra && ./start.sh
```

## License

Private — reference implementation for internal use.

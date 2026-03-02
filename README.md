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

## Domain Model

A product catalog with order management. Two aggregates: **Product** and **Order**.

**Products** follow a lifecycle: DRAFT → ACTIVE → DISCONTINUED. Only ACTIVE products can be ordered. DRAFT products can be deleted (cleanup before publishing). Products with order history are never deleted — they're discontinued instead. The DELETE endpoint exists for housekeeping, not business operations.

**Orders** are immutable once placed. They follow a fulfillment lifecycle: PLACED → CONFIRMED → SHIPPED → DELIVERED, with cancellation possible from PLACED or CONFIRMED. Cancelling an order restores stock. There is no "delete order" operation — cancelled is the terminal state.

**Stock** is decremented atomically at order creation under pessimistic locks (sorted by PK to prevent deadlocks). Price is snapshotted at order time — the order records what the customer paid, not the current catalog price.

**Scope boundaries** (intentional):

- No authentication / authorization — this is an API patterns reference, not a security reference
- No rate limiting — infrastructure concern (reverse proxy / API gateway)
- No API versioning — added when breaking changes happen, not speculatively
- No cache headers — product data is mutable (price, stock, status); caching requires domain-specific TTL decisions
- No soft-delete — hard-delete on DRAFT products only; the lifecycle state machine handles everything else
- Virtual threads enabled (`spring.threads.virtual.enabled=true`) — Java 25 with JEP 491 eliminates `synchronized` pinning

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
├── common/          # Base entity, exceptions, ExceptionTranslator, dev data seeder
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

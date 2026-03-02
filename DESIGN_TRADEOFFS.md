# Design Tradeoffs

## Architecture

| Tradeoff | Rationale |
|---|---|
| Package by feature | Each feature owns its own REST APIs, services, and repository. Spring Modulith-lite |
| API interface / Controller implementation split | HTTP contract separated from handler logic. Stable interface for client generation |
| Thin controllers, fat services | Controllers own HTTP mapping. Services own business logic and transaction boundaries |
| No DTO mapping library | Two entities. Hand-written mappers are simpler than annotation processing and generated code at this scale |
| JSpecify for null safety | Industry standard for null annotations across Java tooling. Ahead of Spring's own annotations, better Kotlin interop, supported by Error Prone and NullAway |
| Virtual threads enabled | JEP 491 (Java 24+) eliminated synchronized-block pinning. I/O-bound requests unmount from platform threads during blocking calls — more throughput, same code |

## Persistence

| Tradeoff | Rationale |
|---|---|
| Time-sorted identifiers for primary keys | TSIDs provide 64-bit time-sortable identifiers. Better B-tree locality than UUID, half the storage, no sequence contention |
| Application-assigned IDs | IDs generated before persistence. Enables correct entity identity across managed and detached states |
| Manual auditing callbacks | No dependency on Spring Data's auditing infrastructure. Simpler without user authentication. Revisit if created-by / updated-by tracking is needed |
| Optimistic locking on all entities | Inherited from base class. Adds a version column to child entities that are never updated independently. Minor waste; not worth a separate base hierarchy |
| BigDecimal for monetary values | Avoids floating-point precision errors. Scale 4 handles 3-decimal currencies and intermediate calculations. Single currency assumed |
| String-mapped enums | Survives enum reordering. Human-readable in SQL queries and migration scripts |
| Flyway for schema management | Explicit versioned DDL. No Hibernate auto-generation. Includes indexes that auto-DDL wouldn't create |
| Partial index on outbox pending events | Only pending rows are indexed. Index stays small regardless of total event volume. PostgreSQL-specific |
| JSONB for event payloads | Write-time JSON validation. Queryable for debugging. More compact than text storage |
| Cascade delete for order line items | Line items have no independent lifecycle. Parent order manages the full child lifecycle |
| No cascading foreign key deletes | Database enforces referential integrity. Application returns 409 when deleting a product referenced by existing orders |
| Fixed connection pool, fail-fast timeout | Ten connections, no auto-scaling. Requests fail in 5 seconds if pool is exhausted rather than queuing indefinitely |

## API Design

| Tradeoff | Rationale |
|---|---|
| Keyset pagination | Avoids OFFSET performance degradation. Stable results under concurrent writes. Single `afterId` cursor — TSIDs are monotonically increasing, so ID alone provides stable ordering. User-supplied limit capped at configured maximum |
| POST for status transitions | Status changes have side effects — domain events, inventory adjustments. POST signals non-idempotent operations. Separate from PATCH for data updates |
| State machine in the domain model | Valid transitions defined per status. Compiler enforces exhaustiveness when new statuses are added |
| Records for request/response types | Immutable. Compact. Cannot distinguish absent from null, so partial updates cannot null-clear fields — documented limitation |
| No hypermedia | Plain JSON responses. API discoverability through documentation, not payload links |

## Concurrency

| Tradeoff | Rationale |
|---|---|
| Pessimistic locking for inventory, optimistic for metadata | Inventory decrement is high-contention — concurrent orders compete for the same products. Metadata updates are low-contention — version-based retry avoids holding row locks |
| 3-second lock timeout | Fail fast under contention. Long enough for normal operations, short enough to surface stuck transactions |
| Sorted lock acquisition | Orders can reference multiple products. Acquiring locks in a consistent order prevents deadlocks between concurrent multi-product orders |
| Server-side optimistic retry | Three attempts with jitter, under one second total. Clients see last-writer-wins. No version exposed in the API — one concurrency strategy, not two layered |

## Events and Outbox

| Tradeoff | Rationale |
|---|---|
| Sealed type hierarchy for domain events | Compiler-enforced exhaustiveness. Adding a new event type without handling it everywhere is a compile error |
| Order events only | Product lifecycle changes don't publish events. One pattern demonstrated well rather than duplicated across entities |
| CloudEvents envelope | Industry-standard event format. Portable across Kafka, EventBridge, Azure Event Grid. Only the producer side is implemented |
| Single payload column | Entire CloudEvents envelope in one JSONB column. Aggregate type and event type stored separately for queries. No schema change when envelope spec evolves |
| Separate publisher component | Retry annotations require proxy interception. Separate bean avoids the self-invocation bypass problem |
| All-replica acknowledgment | Strongest Kafka durability guarantee. Events represent committed business state — losing a write breaks consistency |
| Per-aggregate topics | Preserves ordering within an aggregate. Allows independent consumer groups per domain |

## Error Handling

| Tradeoff | Rationale |
|---|---|
| Exception hierarchy mapped to HTTP status families | Four base types: not-found, conflict, business-rule, unavailable. New domain exceptions inherit the correct HTTP mapping automatically |
| Abstract business rule exception | Forces specific subclasses with structured context. Prevents lazy generic error messages |
| Error codes derived from title strings | Deterministic but fragile. Changing a title silently changes the error code |
| Stack traces only in dev profile | Production responses never expose internal state. 500 responses use a fixed message |

## Observability

| Tradeoff | Rationale |
|---|---|
| Separate management port | Health checks and metrics scraping isolated from API traffic, load balancing, and auth |
| Adaptive trace sampling | Full capture in development. 10% in production to control storage costs |
| Structured logging toggled by environment | Human-readable console format locally. ECS JSON in containers via `logging.structured.format.console=ecs` |
| Auto-instrumented only | No custom business metrics — orders per minute, inventory depletion, outbox lag are absent |

## Testing

| Tradeoff | Rationale |
|---|---|
| Unit / integration test split | Unit tests run fast with mocked dependencies. Integration tests run against real Postgres via Testcontainers |
| Controller test slices | Web layer tested in isolation. No database, no containers. Sub-second feedback |
| Shared test containers | One Postgres and one Kafka instance across all integration tests. Auto-configured datasource and bootstrap servers |
| Query count assertions | Exact SQL count verification per operation. Catches N+1 regressions at test time, not in production |

# Production Readiness Guide

This document is the "why" behind the production hardening of Payment Core. It
is written as a checklist you can reuse when reviewing another service: every
section names the failure it prevents, what the code does, how to verify it, and
where the remaining risk is.

The guiding rule: **production readiness is not a feature list, it is the set of
properties that let you run, observe, and change the service while it fails.**
Everything below exists because something in that sentence was missing.

---

## 1. Delivery reliability

Payment events must not be lost between the database commit and RabbitMQ. The
transactional outbox solves the dual-write problem; publisher confirms solve the
"wrote to the socket" problem.

| Concern | Implementation | Why it matters |
| --- | --- | --- |
| Atomic state + event | `outbox_events` row written in the same transaction as the payment and audit row (`PaymentTransactionService`) | No state without its event, no event without its state |
| Broker acknowledgement | `publisher-confirm-type: correlated`; `ConfirmedMessagePublisher` waits for the ack before `OutboxEvent.markPublished()` | Prevents `PUBLISHED` on a message the broker never stored |
| Unroutable messages | `publisher-returns: true` + mandatory; returned messages fail the attempt | Prevents silently "delivering" a message to no queue (typo in routing key) |
| Bounded wait | `app.outbox.confirm-timeout` (default 5s) | A stuck broker cannot pin the publisher forever |
| Retry + exhaustion | `attempts`, `next_attempt_at`, `max-attempts`; row stays `PENDING`, then `FAILED` | Retries are explicit and observable; poison events stop early instead of hammering the broker |
| Recovery after restart | `OutboxScheduler` polls due rows every `poll-interval-ms` | The only durable recovery path; the immediate dispatcher is best-effort |
| Consumer-side poison messages | each payment queue dead-letters to `payment.events.dlx` → `<queue>.dlq` | A consumer rejection must not drop a payment event |

**Semantics you must keep in mind**

- Delivery is **at-least-once**. A confirm timeout followed by a successful retry
  can produce a duplicate; a crash after the broker ack but before the database
  commit can also produce a duplicate. Consumers must dedupe on
  `messageId`/`eventId`.
- Ordering is per-queue per-consumer, not global. `payment.created` and
  `payment.status-changed` are independent streams.
- `FAILED` is a terminal application state, not a dead letter: the row stays in
  PostgreSQL for inspection and manual replay.
- The publisher holds the outbox row lock during the confirm round trip. This is
  deliberate (no double publish); the company it keeps is a bounded wait. If
  throughput ever demands it, replace the row lock with an atomic
  "claim row — publish — finalize" protocol and accept the duplicate window.

**Verify**

- `OutboxBrokerFailureIntegrationTest` stops the broker application with
  `rabbitmqctl stop_app`, asserts the row stays `PENDING` with `attempts=1`, then
  restarts it and publishes through the scheduler.
- `ConfirmedMessagePublisherTest` covers ack, nack, timeout, returned and
  connection-failure outcomes.

---

## 2. Observability

You cannot operate what you cannot see. The service exposes three complementary
signals: correlation ids in logs, bounded-cardinality metrics, and health
contributors.

### 2.1 Correlation

Every request gets an id:

| Where | Value |
| --- | --- |
| HTTP header | `X-Request-Id` (client value accepted if `[A-Za-z0-9._:-]{8,64}`, otherwise a UUID is generated) |
| Logging MDC | key `requestId` |
| Error payload | Problem Details property `requestId` |

`RequestIdFilter` also rejects ids containing whitespace/control characters, so
a caller cannot forge log lines. The MDC is cleared in a `finally` block because
servlet threads are pooled.

### 2.2 Logs

- Local/default: human-readable pattern that includes `[requestId]`.
- `prod` profile: ECS JSON on stdout (`logging.structured.format.console: ecs`),
  which is the lingua franca of log pipelines (Elastic, OpenSearch, Loki).

Guidance: log identifiers and decisions, never PANs, secrets, or full payloads.
The outbox publisher logs `outboxId`, `eventType`, `aggregateId`, attempt count
and status — enough to reconstruct a delivery incident without leaking data.

### 2.3 Metrics

| Metric | Type | Tags | Use |
| --- | --- | --- | --- |
| `payment.created` | counter | `outcome=CREATED\|REPLAYED` | Traffic + idempotency replay ratio |
| `payment.transition` | counter | `action`, `outcome=APPLIED\|REPLAYED` | Lifecycle health |
| `outbox.publish` | counter | `result=confirmed\|failed`, `reason` | Delivery failure ratio |
| `outbox.publish.duration` | timer | `result`, `reason` | Broker latency vs `confirm-timeout` |
| JVM/HTTP (`http.server.requests`, Hikari, RabbitMQ) | boot-provided | framework tags | Baseline SLOs |

**Cardinality rule:** tags are enums only. A tag with payment id, merchant
reference, or idempotency key creates an unbounded number of time series and will
take down Prometheus before it takes down the service.

Suggested alerts:

- `rate(outbox.publish{result="failed"}[5m]) > 0` for 10 minutes → delivery
  incident.
- `histogram_quantile(0.99, outbox.publish.duration) > 0.8 * confirm-timeout` →
  broker degradation before timeouts start.
- `payment.transition{outcome="REPLAYED"}` suddenly dominating → clients retrying
  because of a downstream problem.
- `outbox.publish{reason="connection"}` spike → broker/network outage.

### 2.4 Health

- `/actuator/health/liveness` and `/actuator/health/readiness` are enabled; wire
  them to Kubernetes probes, not to a load balancer that restarts pods on
  transient database hiccups.
- The `outbox` health contributor reports `pending` and `failed` counts and
  turns `DOWN` only when exhausted events exceed
  `OUTBOX_FAILED_HEALTH_THRESHOLD`. Pending rows during a retry window are
  normal and must not fail readiness, otherwise an outage becomes a restart
  loop.
- Health details are hidden in `prod` (`show-details: never`); operators use the
  metrics/logs, callers get a coarse status.

---

## 3. Security

- `app.security.mode` is an explicit enum: `OPEN` or `JWT`.
- `SecurityModePolicy` fails application startup when `prod` is active and the
  mode is `OPEN`. A "temporarily open" production service is a security
  incident, not a configuration choice.
- The `prod` profile defaults to `JWT` and disables Swagger/OpenAPI at runtime;
  the API contract is still generated and reviewed at build time.
- Public surface: health, info, Prometheus, Swagger (non-prod). Everything else
  is authenticated in JWT mode.
- `management.endpoints.web.exposure.include` is restricted per profile
  (`health,info,prometheus` in prod).

Remaining risks to address at the platform edge: TLS termination, network
policy restricting `/actuator/prometheus` to the monitoring namespace, rate
limiting (Redis is provisioned but not wired yet), and token scope/merchant
ownership checks once multi-tenancy exists.

---

## 4. Configuration

| Profile | Purpose | Notable overrides |
| --- | --- | --- |
| default | Local development | Open security, human logs, Swagger on |
| `local` | Debugging | `com.paymentcore=DEBUG` |
| `prod` | Deployed service | JWT, ECS JSON logs, no Swagger, hidden health details, `forward-headers-strategy` |

- All configuration properties are constructor-bound records with
  `@Validated`: missing or nonsensical values fail at startup
  (`OutboxProperties`, `MessagingProperties`, `SecurityProperties`).
- Secrets come from the environment/secret manager, never from
  `application.yml`; `.env.example` documents the shape without values.
- Add new settings as typed properties, not as `@Value` lookups: they are then
  validated, documented, and discoverable in the IDE.

---

## 5. Data

- Flyway is forward-only. Migrations are small, reviewed, and run before the
  app starts.
- Constraints live in the database, not only in Java: status domains, lifecycle
  timestamp invariants, positive amount, foreign keys. The application's state
  machines are the first line; the database is the last.
- Indexes added in `V4__operational_indexes.sql` exist for operational queries
  (idempotency retention, failed-outbox forensics) rather than for the happy
  path.
- Pessimistic locks protect payment transitions and outbox publishing; the
  optimistic `@Version` remains as a stale-write guard.
- Gap: there is no retention job yet for `idempotency_keys`. The index is in
  place; the scheduled cleanup is roadmap work.

---

## 6. Build and delivery

| Gate | Tool | What it catches |
| --- | --- | --- |
| Toolchain | Maven Enforcer | Wrong JDK/Maven; duplicate dependency versions |
| Architecture | ArchUnit (`ArchitectureTest`) | Domain depending on services, controllers reaching into repositories, cyclic package slices, field injection, naming drift |
| Tests | Surefire + Testcontainers | Real PostgreSQL, RabbitMQ and Redis semantics (constraints, confirms, DLQ args) |
| Coverage | JaCoCo `check` on `verify` (80% lines) | Silent regression in tested behaviour |
| CI | `.github/workflows/ci.yml` | Runs the full `verify` on every push/PR, uploads reports |

Why an architecture test instead of a wiki page: diagrams rot, tests fail. The
rules in `ArchitectureTest` are the minimum contract (domain purity, API →
service boundary, acyclic slices), not a complete design document.

The Docker image is multi-stage, runs as a non-root user, and has a container
health check. Image scanning (Trivy/Grype) and SBOM generation are the next
supply-chain steps.

---

## 7. Operations

See [runbook.md](runbook.md) for incident playbooks, SQL queries and escalation.
Key habit: every alert should point at one metric/dashboard and one runbook
entry. If it does not, the alert is noise.

---

## 8. Known gaps (deliberate, documented)

1. Idempotency key retention/cleanup job.
2. Outbox replay endpoint for `FAILED` rows (today: controlled SQL, see runbook).
3. Consumers and their retry policy (DLQ exists; nothing consumes yet).
4. Quorum queues instead of classic durable queues for stronger broker-side
   durability.
5. OpenTelemetry tracing across HTTP → DB → RabbitMQ.
6. Rate limiting backed by Redis (`payment.*` endpoints).
7. Contract tests for downstream event schemas.
8. Load test with confirm latency under broker pressure.
9. Secrets management, TLS, WAF, and Prometheus network isolation at the edge.

Each of these is an independent, testable increment — which is the point of a
production-ready baseline: you add capabilities without redesigning the core.

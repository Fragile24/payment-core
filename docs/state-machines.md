# Explicit State Machines

This document is the reference for every state machine that is now explicit in
the Payment Core backend. It distinguishes domain machines from runtime and
infrastructure states owned by Spring, Flyway, Docker, or RabbitMQ.

## Inventory

| State machine | Explicit model | Persistence | Owner |
| --- | --- | --- | --- |
| Payment lifecycle | `PaymentAction`, `PaymentStatus`, `PaymentStateMachine` | `payments.status`, lifecycle timestamps, `ck_payments_lifecycle_timestamps` | Application + PostgreSQL |
| Idempotency | `IdempotencyRecord`, `PaymentCreationOutcome` | `idempotency_keys` | Application + PostgreSQL |
| API mutation outcome | `PaymentCreationOutcome`, `PaymentTransitionOutcome` | Response headers/status | Application |
| Event delivery | `OutboxEvent`, `OutboxStatus`, `OutboxStateMachine` | `outbox_events` | Application + PostgreSQL + RabbitMQ |
| Projection read model | `ProjectionOutcome`, `payment_projection`, `processed_events` | `payment_projection`, `processed_events` | Application + RabbitMQ + PostgreSQL |
| Reconciliation | `ReconciliationOutcome`, findings/runs | `reconciliation_runs`, `reconciliation_findings`, `scheduled_job_lock` | Application + PostgreSQL |
| Audit projection | `PaymentAuditLog`, `PaymentAuditEventType` | `payment_audit_logs` | Application + PostgreSQL |
| Concurrency/version | `@Version`, `findByIdForUpdate` | `payments.version`, row locks | Application + PostgreSQL |
| Security mode | `SecurityMode` | Configuration | Application |
| Runtime/container | Spring Boot + Docker Compose configuration | Docker engine | Spring Boot / Docker |
| Schema migration | Flyway migrations V1–V6 | `flyway_schema_history` | Flyway |
| Rabbit connection | Spring AMQP connection factory | Broker/connection | Spring AMQP |
| Transaction phase | `@Transactional`, `@TransactionalEventListener` | Database transaction | Spring |

## Payment lifecycle

Single source of truth:

- `com.paymentcore.payment.domain.PaymentAction`
- `com.paymentcore.payment.domain.PaymentStateMachine`
- `com.paymentcore.payment.domain.Payment.apply(PaymentAction)`

| From \ Action | AUTHORIZE | CAPTURE | REFUND | CANCEL | FAIL |
| --- | --- | --- | --- | --- | --- |
| `INITIATED` | `AUTHORIZED` | 409 | 409 | `CANCELLED` | `FAILED` |
| `AUTHORIZED` | replay | `CAPTURED` | 409 | `CANCELLED` | `FAILED` |
| `CAPTURED` | 409 | replay | `REFUNDED` | 409 | 409 |
| `REFUNDED` | 409 | 409 | replay | 409 | 409 |
| `CANCELLED` | 409 | 409 | 409 | replay | 409 |
| `FAILED` | 409 | 409 | 409 | 409 | replay |

- A **replay** returns `200`, `Idempotent-Replayed: true`,
  `Transition-Outcome: REPLAYED`, and creates no audit row or outbox row.
- An **invalid transition** returns `409 INVALID_PAYMENT_STATE` with
  `currentStatus`, `action`, and `targetStatus`.
- Every applied transition updates the corresponding timestamp
  (`authorized_at`, `captured_at`, `refunded_at`, `cancelled_at`, `failed_at`)
  and writes one `PAYMENT_STATUS_CHANGED` audit row plus one outbox row in the
  same transaction.
- Transitions acquire a pessimistic row lock
  (`PaymentRepository.findByIdForUpdate`), so concurrent actions serialize.
- Database constraints enforce the allowed status values and the timestamp
  invariant for every state (`V3__explicit_state_machines.sql`).

## Idempotency

The key state is no longer derived from the payment row. It is represented by
an explicit `idempotency_keys` record:

```text
UNUSED
  └─ create payment + insert record + store response snapshot
       └─ STORED
            ├─ same request hash  → REPLAY (return stored snapshot)
            └─ different hash     → CONFLICT (409)
```

- `com.paymentcore.payment.domain.IdempotencyRecord`
- Unique constraint: `idempotency_keys.idempotency_key`
- Stored fields: request hash, payment id, response status, response body,
  created timestamp.
- The response body is a JSON snapshot of the original creation response.
  Replaying a create after lifecycle changes returns the original snapshot,
  not the current payment state.
- A concurrent first request is resolved by the unique constraint plus the
  retry loop in `PaymentService.create`; the losing transaction rolls back its
  payment, audit, and outbox rows and replays the winning record.
- The request fingerprint carries an explicit algorithm version (`v1`). V3
  backfills existing hashes as-is; keys created with a pre-v1 algorithm will
  not match new retries. A production migration should store the hash version
  and accept legacy hashes during a transition period.

## API mutation outcomes

Explicit enums replace booleans in application code:

- `PaymentCreationOutcome.CREATED` / `REPLAYED`
- `PaymentTransitionOutcome.APPLIED` / `REPLAYED`

Response signals:

| Outcome | HTTP | Header |
| --- | --- | --- |
| Create, first time | `201` | `Idempotent-Replayed: false`, `Idempotency-Outcome: CREATED` |
| Create, replay | `200` | `Idempotent-Replayed: true`, `Idempotency-Outcome: REPLAYED` |
| Transition applied | `200` | `Idempotent-Replayed: false`, `Transition-Outcome: APPLIED` |
| Transition replay | `200` | `Idempotent-Replayed: true`, `Transition-Outcome: REPLAYED` |
| Invalid state | `409` | Problem Details `INVALID_PAYMENT_STATE` |

## Outbox / event delivery

State machine:

```text
PENDING ──success──────────────> PUBLISHED
PENDING ──failure, attempts<N──> PENDING (next_attempt_at)
PENDING ──attempts>=N──────────> FAILED
FAILED  ──manual retry─────────> PENDING
```

- Model: `OutboxEvent`, `OutboxStatus`, `OutboxStateMachine`
- Table: `outbox_events` with `ck_outbox_events_status`
- Every payment mutation inserts an outbox row in the same database
  transaction as the payment and audit row.
- After commit, `OutboxImmediateDispatcher` attempts an immediate publish.
  It uses `REQUIRES_NEW` because the original transaction has already
  completed.
- `OutboxScheduler` retries due `PENDING` rows every
  `app.outbox.poll-interval-ms` (default 5 seconds).
- `OutboxPublisher` locks one row with `PESSIMISTIC_WRITE` and publishes the
  stored JSON payload through `ConfirmedMessagePublisher`. The row only moves
  to `PUBLISHED` after RabbitMQ confirms the message
  (`spring.rabbitmq.publisher-confirm-type=correlated`).
- The confirm wait is bounded by `app.outbox.confirm-timeout` (default 5
  seconds). The row lock is held for that round trip, which is the price of
  guaranteeing that two dispatchers cannot publish the same row.
- A nack, a confirm timeout, a returned (unroutable) message and a connection
  failure all record a failed attempt: `attempts + 1`, `last_error`,
  `next_attempt_at`. The row stays `PENDING` until `max-attempts` is reached.
- `spring.rabbitmq.publisher-returns=true` plus the mandatory flag make
  unroutable messages come back to the publisher instead of being dropped
  silently; they are treated as failed publishes.
- Attempts and next retry time are persisted; exhausted events become
  `FAILED`.
- Queues:
  - `payment.created` / routing key `payment.created`
  - `payment.status-changed` / routing key `payment.status-changed`
  - `payment.projection` / bound to both routing keys (consumer group queue)
- Dead letters: each queue declares `x-dead-letter-exchange=payment.events.dlx`
  with its own name as routing key, and each `<queue>.dlq` is bound to that
  exchange with the source queue name as routing key. The outbox covers
  publisher failures; dead-lettering covers consumer failures, which the outbox
  cannot observe.
- Cross-queue ordering is not guaranteed, and even within one queue a
  redelivery can arrive after a newer event. Events carry `paymentVersion`
  (aggregate version) so consumers apply only newer state instead of relying on
  arrival order.
- `PUBLISHED` now means RabbitMQ acknowledged the message, not just that the
  frame reached the socket. Delivery remains at-least-once: a confirm that
  arrives after the timeout, followed by a retry, can produce a duplicate.
  Consumers must dedupe on `messageId`/`eventId`; every message also carries an
  `outboxAttempts` header.

## Projection consumer and reconciliation

The first consumer builds `payment_projection` (see
[ADR 0002](adr/0002-projection-consumer-and-reconciliation.md)).

```text
payment.projection queue
    │  delivery id = message id
    ▼
processed_events (event_id, consumer)  ── conflict ──> DUPLICATE, ignored
    │ new
    ▼
payment_projection upsert  WHERE version < event.paymentVersion
    │
    ▼
commit both in one transaction
```

- `eventId` is the domain event id; `messageId` is the stable delivery id used
  for deduplication across redeliveries.
- Retry policy: 3 attempts with exponential backoff, then reject without requeue
  → `payment.projection.dlq`.
- Reconciliation compares `payments` with `payment_projection` on a schedule
  behind a database lease. It repairs only missing/stale projections, from
  `payments`, and records every run and finding in `reconciliation_runs` /
  `reconciliation_findings`. Orphan projections and outbox failures are
  reported, never auto-deleted.

## Audit projection

`payment_audit_logs` is an append-only projection, not an independent state
machine. It has:

- `PAYMENT_CREATED` for the creation edge
- `PAYMENT_STATUS_CHANGED` for every applied lifecycle edge
- `from_status` / `to_status` columns
- database constraints for event type and status domains

Audit rows are written in the same transaction as the state change. Idempotent
replays and rejected transitions intentionally do not create audit rows.

Ordering currently uses `created_at`; a future improvement is a monotonic
per-payment sequence or a UUIDv7 event id.

## Concurrency and version

- Transitions use a pessimistic write lock so only one state change per
  payment can be in flight.
- The payment row also carries an optimistic `@Version`.
- `PaymentResponse` exposes `version`.
- `OptimisticLockingFailureException` maps to `409 CONCURRENT_MODIFICATION`.
- The two mechanisms are deliberate: the lock protects business transitions;
  the version is an additional stale-write guard and a client-visible
  generation number.

## Security mode

`SecurityMode` is an explicit enum:

- `OPEN` — local sandbox; all payment endpoints are permitted and a startup
  warning is logged.
- `JWT` — OAuth2 Resource Server; all non-public endpoints require a bearer
  token.

Configuration:

```yaml
app:
  security:
    mode: ${SECURITY_MODE:OPEN}
```

The `jwt` profile sets `JWT` and configures
`spring.security.oauth2.resourceserver.jwt.issuer-uri` from
`OAUTH2_ISSUER_URI`.

## Infrastructure states

These are explicit in configuration or documentation, but not business state:

- **Runtime/container:** Docker Compose lifecycle is `start-only`; stopping
  the app does not stop infrastructure. Readiness waits only for services
  started by the app. Shutdown is graceful with a 20-second phase timeout.
- **Schema migration:** Flyway is forward-only. V3 moves idempotency columns
  into `idempotency_keys`, adds lifecycle constraints, and creates the outbox
  table. V4 adds operational indexes, V5 creates the projection read model and
  delivery-idempotency tables, V6 creates the reconciliation tables and job
  lease.
- **Scheduling:** `@Scheduled` owns the outbox retry tick and the reconciliation
  tick. The reconciliation lease lives in PostgreSQL, not in the JVM, so two
  replicas cannot sweep at the same time.
- **Rabbit connection:** Spring AMQP owns connection/channel recovery. The
  outbox is the application-owned retry state; template retry was removed so
  there is no second hidden retry machine. The shared connection factory has
  correlated publisher confirms and returns enabled, so a connection failure
  surfaces as a failed publish attempt rather than a silent loss.
- **Transaction phase:** `@Transactional` owns active/commit/rollback.
  Immediate outbox dispatch runs after commit in a new transaction and is
  allowed to fail safely; the scheduler is the recovery path.

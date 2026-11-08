# ADR 0001 — Transactional outbox with publisher confirms

- **Status:** Accepted
- **Date:** 2026-11
- **Deciders:** Payment Core maintainers

## Context

Creating a payment and emitting `PAYMENT_CREATED` must not diverge. The naive
implementation writes the row, commits, then calls RabbitMQ:

```java
payments.save(payment);
// transaction commits here
rabbitTemplate.convertAndSend(exchange, routingKey, event);
```

Two failure windows follow:

1. The broker call fails or the process crashes after commit → the payment
   exists, the event never leaves. Downstream ledgers and notifications never
   see it.
2. The broker call succeeds but the database transaction rolls back → consumers
   process an event for a payment that does not exist.

`basic.publish` is also asynchronous: a successful `send` only means the frame
reached the socket. The broker can still drop it (queue not durable, node lost,
routing miss).

## Decision

1. Write the event to an `outbox_events` row **in the same database transaction**
   as the payment and the audit row.
2. After commit, a best-effort dispatcher tries to publish immediately; a
   scheduled publisher retries anything still `PENDING`.
3. Publish with `CorrelationData` and `publisher-confirm-type: correlated`.
   The row only becomes `PUBLISHED` after the broker ack.
4. Enable `publisher-returns` and the mandatory flag. A message the broker cannot
   route comes back and is treated as a failed attempt, not a success.
5. Bound the confirm wait with `app.outbox.confirm-timeout`; failures increment
   `attempts` and schedule `next_attempt_at`, until `max-attempts` moves the row
   to `FAILED`.

The outbox row is locked with `PESSIMISTIC_WRITE` during publish so two
dispatchers cannot publish the same row.

## Consequences

**Positive**

- State and event are atomic; a committed payment always has a committed event.
- `PUBLISHED` is a real end-to-end acknowledgment (broker returned success).
- Broker outages degrade to a growing `PENDING` backlog, not data loss.
- The row is a durable audit trail of every delivery attempt (`attempts`,
  `last_error`, `next_attempt_at`, `published_at`).
- Unroutable messages surface as failures and metrics instead of vanishing.

**Negative / trade-offs**

- At-least-once: duplicates are possible (timeout + retry, crash after ack).
  Consumers must dedupe on `messageId`/`eventId`.
- The publisher holds a database row lock during the broker round trip.
  Throughput is bounded by broker latency; the wait is capped by the confirm
  timeout.
- An extra table and scheduler to operate; backlog/health needs monitoring.
- `FAILED` rows require an operational replay procedure (documented in the
  runbook) because nothing retries them automatically.

## Alternatives considered

- **Dual write with client-side retry.** Cannot be made atomic; at least one
  window of inconsistency always remains.
- **Change data capture (Debezium + Kafka).** Strong option at scale: the
  database log becomes the event stream and the application stops publishing.
  Rejected here because it adds a platform dependency that the learning scope
  does not need; the outbox table maps cleanly onto CDC later.
- **Fire-and-forget publish + reconciliation job.** Reconciliation cannot
  reconstruct an event that was never written; it only detects the gap after the
  fact.
- **Publisher confirms without the outbox.** Confirms close the socket window
  but not the commit/publish window. Both are required.

## Follow-ups

- Dead-letter queues for consumer-side failures (implemented in the same
  iteration).
- Outbox metrics and an `outbox` health contributor (implemented).
- Manual replay endpoint for `FAILED` rows (roadmap).
- Quorum queues and OpenTelemetry tracing (roadmap).

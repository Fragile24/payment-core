# ADR 0002 — Projection consumer and scheduled reconciliation

- **Status:** Accepted
- **Date:** 2026-11
- **Deciders:** Payment Core maintainers

## Context

The outbox guarantees that every committed payment produces a published event.
Nothing consumed those events. The first real consumer is an in-house read model
(`payment_projection`) used for queries and reporting without touching the
payment aggregate.

Building a consumer raises the questions the outbox cannot answer:

1. The broker delivers **at least once**; the consumer must be idempotent.
2. Deliveries can arrive **out of order** (redelivery after a restart, retry
   while another message is in flight). A projection must never move backwards.
3. A consumer bug or poison payload must not block the queue forever.
4. Consumers can still lose or mis-apply events (crash between broker ack and
   database commit). Something must **detect and repair** the resulting drift.

## Decision

### Consumer

- The consumer group owns its queue: `payment.projection`, bound to both
  `payment.created` and `payment.status-changed`. Publisher topology does not
  change when consumers are added, and the queue is not shared with other
  consumers (fan-out, not work-sharing across groups).
- Each delivery is recorded in `processed_events (event_id, consumer)` with
  `INSERT ... ON CONFLICT DO NOTHING` **inside the same transaction** as the
  projection write. A duplicate delivery returns 0 inserted rows and is skipped.
- Each event carries `paymentVersion`, the aggregate version at emission. The
  projection upsert includes `WHERE payment_projection.version < excluded.version`,
  so an older or duplicate event cannot overwrite a newer state — the ordering
  rule is enforced atomically by the database, not by consumer-side locking.
- The listener retries three times (one attempt plus two retries) with a short
  exponential backoff. Afterwards `RejectAndDontRequeueRecoverer` rejects the
  message without requeue, and the queue's dead-letter exchange moves it to
  `payment.projection.dlq`.

### Reconciliation

- A scheduled sweep runs every `app.reconciliation.interval-ms` (default 5
  minutes) behind a database lease (`scheduled_job_lock`), so concurrent
  instances cannot sweep twice.
- It compares `payments` with `payment_projection` (missing rows, and status or
  version mismatch), counts orphan projections, and reports the outbox backlog
  (`PENDING`, stuck `PENDING`, `FAILED`).
- Only the **projection** is repaired, and only from the source of truth
  (`payments`), never by republishing events. Source rows are never mutated:
  rewriting the source of truth would hide the bug that caused the drift.
- Each run is bounded by `app.reconciliation.batch-size` (default 500) and
  persists a `reconciliation_runs` row plus `reconciliation_findings` so an
  operator can see what happened after the fact.
- Metrics: `reconciliation.runs{result}`, `reconciliation.duration`,
  `reconciliation.findings{type}`, `reconciliation.repaired`.

## Consequences

**Positive**

- Duplicate-safe and order-safe consumption without distributed locks.
- Read model drift is detected within one interval and repaired idempotently.
- Poison messages are isolated in a DLQ instead of blocking the queue.
- Reconciliation is observable and auditable; every run is a row, every problem
  a finding.

**Negative / trade-offs**

- The projection is eventually consistent; a read immediately after a write may
  not see it.
- Reconciliation is a full-scan comparison (bounded). On very large tables it
  needs an incremental/watermark strategy or should run against a replica.
- Orphan projections and failed outbox events are reported, not auto-fixed; the
  runbook owns those.
- The lease relies on the database clock; multi-region deployments need a
  single writer per region or a different coordination mechanism.

## Alternatives considered

- **Consume the existing `payment.created` / `payment.status-changed` queues.**
  Consumer groups would compete for messages with other consumers and with the
  integration tests. A dedicated queue per group is the RabbitMQ-native model.
- **Exactly-once delivery.** Not available end-to-end with RabbitMQ and a
  separate database. Idempotent consumption plus reconciliation is the honest
  engineering answer.
- **Republish events instead of re-projecting.** Rebuilding from the source row
  is simpler, cannot reorder the stream, and does not depend on the outbox
  history.
- **Event sourcing / CDC (Debezium).** Stronger long-term options; rejected for
  the same reason as in ADR 0001 — the learning scope does not need another
  platform component yet.

## Follow-ups

- Split the consumer into its own deployable if read-model load diverges from
  the payment API.
- Admin-triggered reconciliation and DLQ replay tooling.
- Incremental reconciliation using a watermark (`payments.updated_at`).

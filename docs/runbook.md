# Runbook

Operational procedures for Payment Core. Each section starts from a symptom, not
from a component: during an incident you know what users report, not which bean
is broken.

## Fast orientation

| Endpoint / signal | Meaning |
| --- | --- |
| `GET /actuator/health/liveness` | Process is alive; failure means restart the pod |
| `GET /actuator/health/readiness` | Process can serve traffic; failure means remove it from rotation |
| `GET /actuator/health` | Includes the `outbox` contributor (`pending`, `failed`, threshold) |
| `GET /actuator/prometheus` | Metrics (`payment.*`, `outbox.*`, HTTP, JVM, pools) |
| Logs | One JSON line per event in `prod`; filter by `requestId` to follow one request |

## Playbook 1 — Outbox backlog is growing

**Signals:** `outbox.publish{result="failed"}` non-zero, `/actuator/health`
detail `pending` climbing, queue `payment.created` not receiving.

1. Group rows by status and error:
   ```sql
   SELECT status, count(*) FROM outbox_events GROUP BY status;

   SELECT id, attempts, next_attempt_at, left(last_error, 160) AS error
   FROM outbox_events
   WHERE status = 'PENDING'
   ORDER BY next_attempt_at
   LIMIT 20;
   ```
2. Check the failure reason metric for the dominant cause:
   - `reason="connection"` → playbook 2 (broker/network).
   - `reason="timeout"` → broker overloaded or `confirm-timeout` too tight.
   - `reason="nack"` → broker resource alarm (disk/memory) or policy.
   - `reason="returned"` → routing key/topology mismatch; inspect
     `exchange`/`routing_key` on the rows and the bindings.
3. Do not mass-update rows while the publisher is running; it locks rows with
   `SELECT ... FOR UPDATE` and a bulk `UPDATE` will block or deadlock with it.

## Playbook 2 — RabbitMQ unavailable

**Signals:** `outbox.publish{reason="connection"}` spike, `/actuator/health`
`outbox` pending grows, `payment.created` queue empty, application logs with
`AmqpConnectException`.

1. Verify the broker from inside the network:
   ```bash
   rabbitmq-diagnostics -q ping
   rabbitmqctl list_queues name messages consumers
   ```
2. The application is expected to stay healthy for the API while the outbox
   retries: payment creation/transitions do not depend on the broker.
3. When the broker returns, delivery resumes automatically on the next
   `OUTBOX_POLL_INTERVAL_MS` tick. Watch `outbox.publish{result="confirmed"}`
   and the `pending` count going down. No manual replay is needed.
4. If rows exhausted `max-attempts` during the outage (`status='FAILED'`), follow
   playbook 4.

## Playbook 3 — Payment API 5xx

1. Correlate by `requestId` from the client error payload:
   ```bash
   # prod logs are JSON; the field is requestId
   ```
2. `CONCURRENT_MODIFICATION` / `INVALID_PAYMENT_STATE` are 409s by design, not
   5xx. If 5xx dominates, check database pool saturation
   (`hikari_connections_pending`), then the `timeout`/`connection` outbox reason:
   publishing happens after commit and can slow the request thread.
3. Confirm the migrations applied: `/actuator/health` includes a `db` check by
   default; a failed Flyway migration aborts startup, so an old replica typically
   means a bad image, not a bad query.

## Playbook 4 — `FAILED` outbox events

Rows are kept for inspection; nothing retries them automatically.

1. Inspect one row:
   ```sql
   SELECT id, aggregate_id, event_type, attempts, last_error, created_at
   FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at LIMIT 10;
   ```
2. Fix the cause first (binding, broker capacity, payload). Replaying a poison
   payload only produces another `FAILED` row.
3. Replay manually and re-arm the scheduler:
   ```sql
   UPDATE outbox_events
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
   WHERE id = '<id>' AND status = 'FAILED';
   ```
   This bypasses the state machine, so it is an operator-only action; keep it
   recorded in the incident ticket.

## Playbook 5 — Dead-letter queue has messages

Queues: `payment.created.dlq`, `payment.status-changed.dlq` on exchange
`payment.events.dlx`.

1. Inspect without consuming:
   ```bash
   rabbitmqctl list_queues name messages
   # Management UI → Queues → Get messages (Ack mode: Nack/Requeue) to peek
   ```
2. Typical causes: consumer exception, schema mismatch after an event change,
   handler timeout.
3. After deploying the fix, move messages back with the management UI ("Move
   messages") or `rabbitmqadmin`, then watch the consumer lag.
4. Never purge a DLQ before capturing the payloads; they are evidence.

## Playbook 6 — Failed deployment / migration

- Flyway is forward-only. A failed migration leaves the schema history in a
  failed state and the application will not start.
- Roll back by deploying the previous image **only if** the migration is
  backwards compatible. Otherwise restore the database snapshot taken before the
  release (documented release step, not an improvization).
- Re-run with `flyway repair` only after understanding why the checksum/state
  diverged.

## Playbook 7 — Changing queue arguments

RabbitMQ queue arguments (dead-letter exchange, TTL, queue type) are immutable:
declaring an existing queue with different arguments fails with
`PRECONDITION_FAILED - inequivalent arg`.

Changing e.g. the DLX or moving to quorum queues therefore requires an explicit
migration, not a redeploy:

1. Stop consumers (or the whole app, since the publisher does not consume).
2. Drain or move messages from the old queue.
3. Delete the queue, deploy the new topology, verify bindings and arguments:
   ```bash
   rabbitmqctl list_queues name arguments
   ```
4. Replay the drained messages.

Alternatively, use a broker-side **policy** (`rabbitmqctl set_policy`) for
arguments RabbitMQ allows policies to change; DLX/DLQ routing is not one of
them. Record whichever path you take in the change ticket.

## Escalation

| Condition | Owner | Action |
| --- | --- | --- |
| Outbox `pending` > 1h of traffic, or confirmed rate = 0 | Platform on-call | Broker/network playbook |
| `FAILED` rows and no matching deploy | Payments engineer | Inspect payloads, fix consumer/topology |
| 5xx > 1% for 5 minutes or `payment.created` error rate rising | Service owner | Roll back if a release is adjacent |
| Suspected data exposure in logs | Security | Rotate, purge, postmortem |

## Useful commands

```bash
# Local stack
docker compose up -d --wait
docker compose logs -f rabbitmq

# Application
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Tests (needs Docker)
./mvnw verify

# Broker inspection (compose service)
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
docker compose exec rabbitmq rabbitmq-diagnostics -q ping
```

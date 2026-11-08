# payment_sandbox

# Payment Core

## Tổng quan

Payment Core là project sample theo hướng  Pay Sandbox, một nền tảng thanh toán số mini giúp team engineering practice các kỹ thuật quan trọng trong dự án fintech như transaction design, idempotency, AI guardrails, Azure Cloud, Flutter và event-driven architecture.

Mục tiêu chính của project không phải là xây thật nhiều tính năng, mà là hiểu và trình bày được cách thiết kế hệ thống thanh toán production-ready: xử lý giao dịch nhất quán, chống duplicate request, phục hồi khi network/API/system failure, ghi audit log, monitoring, observability và tích hợp AI Payment Assistant một cách an toàn.

Sau khi hoàn thành, project cần có phần present về system design và failure handling để giải thích rõ flow thanh toán hoạt động như thế nào, khi lỗi xảy ra thì hệ thống retry, rollback, reconcile, log và cảnh báo ra sao.

## Tech stack dự kiến

| Nhóm | Công nghệ dự kiến |
| --- | --- |
| Backend | Java Spring Boot hoặc .NET ASP.NET Core |
| Database | PostgreSQL / SQL Server |
| Cache | Redis |
| Message Queue | RabbitMQ / Kafka |
| Authentication | JWT / OAuth2 / OpenID Connect |
| API Documentation | Swagger / OpenAPI |
| CI/CD | Jenkins và GitLab CI |
| Container | Docker |
| Deployment | Azure VM / Azure App Service / AKS |
| Monitoring | Prometheus / Grafana / Azure Monitor |

## Vai trò các thành phần

| Thành phần | Vai trò |
| --- | --- |
| Developer | Push code lên Git |
| Git Repository | Nơi lưu source code, ví dụ GitHub, GitLab, Azure Repos, Bitbucket |
| Jenkins Server | CI/CD server, chịu trách nhiệm build, test, package và deploy |
| Jenkins Pipeline | Quy trình tự động hóa triển khai code |
| Container Registry | Nơi lưu Docker image |
| Dev Environment | Môi trường dành cho developer test nhanh |


## Luồng CI/CD tổng quan

1. Developer push code lên Git Repository.
2. Jenkins Pipeline được kích hoạt tự động.
3. Jenkins Server build source code, chạy test.
4. Docker image được đẩy lên Container Registry.
5. Hệ thống deploy lên Dev Environment để test nhanh.

## Ghi chú

Tech stack có thể thay đổi tùy theo yêu cầu thực tế của dự án, năng lực team và hạ tầng triển khai.

---

# Backend Spring Boot (khởi tạo)

Phần backend đã được khởi tạo với Spring Boot và tập trung vào các nội dung đầu tiên của Payment Core: **transaction design**, **idempotency**, **payment lifecycle** và **audit/observability**.

## Thành phần đã có

| Thành phần | Công nghệ |
| --- | --- |
| Runtime | Java 21, Spring Boot 4.1.2 |
| Build | Maven Wrapper (`mvnw`) |
| Payment lifecycle | `INITIATED → AUTHORIZED → CAPTURED → REFUNDED`, cancel/fail trước capture |
| State machines | Transition matrix trong code + DB constraints + [docs/state-machines.md](docs/state-machines.md) |
| Event delivery | Transactional outbox + publisher confirms + retry scheduler + durable RabbitMQ queues |
| Database | PostgreSQL 17 + Flyway migration |
| Cache | Redis 8 (đã có hạ tầng, sẽ dùng cho rate limit/idempotency cache ở bước sau) |
| Message Queue | RabbitMQ 4 (topic exchange + durable queue) |
| Security | Spring Security, OAuth2 Resource Server (JWT), tắt auth cho local |
| API Docs | Swagger UI / OpenAPI 3 |
| Monitoring | Spring Boot Actuator + Prometheus endpoint |
| Container | Docker Compose cho hạ tầng, Dockerfile multi-stage cho ứng dụng |

## Yêu cầu môi trường

- JDK 21 (project đã được kiểm tra với Eclipse Temurin 21)
- Docker Desktop đang chạy
- Không cần cài Maven; dùng Maven Wrapper đi kèm

## Chạy local

```powershell
docker compose up -d --wait
.\mvnw.cmd spring-boot:run
```

macOS/Linux:

```bash
docker compose up -d --wait
./mvnw spring-boot:run
```

Spring Boot Docker Compose support cũng có thể tự khởi động PostgreSQL/Redis/RabbitMQ khi chạy `spring-boot:run` nếu Docker đang chạy. Lệnh `docker compose up -d --wait` giúp chủ động kiểm soát hạ tầng và đảm bảo database sẵn sàng trước khi ứng dụng start.

Dừng hạ tầng sau khi test:

```powershell
docker compose down
```

Build và chạy file jar:

```powershell
.\mvnw.cmd clean package
java -jar target\payment-core-0.0.1-SNAPSHOT.jar
```

Các URL local:

| URL | Mục đích |
| --- | --- |
| http://localhost:8080/swagger-ui.html | Swagger UI |
| http://localhost:8080/v3/api-docs | OpenAPI JSON |
| http://localhost:8080/actuator/health | Health check |
| http://localhost:8080/actuator/prometheus | Prometheus metrics |
| http://localhost:15672 | RabbitMQ Management UI (`payment` / `payment`) |

## API đã có

| Method | Path | Mô tả |
| --- | --- | --- |
| `POST` | `/api/v1/payments` | Tạo payment, bắt buộc header `Idempotency-Key` |
| `POST` | `/api/v1/payments/{id}/authorize` | `INITIATED → AUTHORIZED` |
| `POST` | `/api/v1/payments/{id}/capture` | `AUTHORIZED → CAPTURED` |
| `POST` | `/api/v1/payments/{id}/refund` | `CAPTURED → REFUNDED` (full refund) |
| `POST` | `/api/v1/payments/{id}/cancel` | `INITIATED` hoặc `AUTHORIZED → CANCELLED` |
| `POST` | `/api/v1/payments/{id}/fail` | `INITIATED` hoặc `AUTHORIZED → FAILED` (failure simulation) |
| `GET` | `/api/v1/payments/{id}` | Lấy payment theo id |
| `GET` | `/api/v1/payments?page=0&size=20` | Danh sách payment, mới nhất trước |
| `GET` | `/api/v1/payments/{id}/audit` | Audit trail của payment |

### Ví dụ tạo payment

> Trên Windows PowerShell, dùng `curl.exe` thay cho `curl` để tránh alias mặc định của `Invoke-WebRequest`.

```bash
curl -i -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: checkout-0001" \
  -d '{
    "amount": 125.50,
    "currency": "USD",
    "merchantReference": "ORDER-2026-0001",
    "description": "Sandbox order"
  }'
```

- Lần đầu: `201 Created`, header `Idempotent-Replayed: false`, `Idempotency-Outcome: CREATED`.
- Retry cùng key và cùng payload: `200 OK`, header `Idempotent-Replayed: true`, `Idempotency-Outcome: REPLAYED`, trả lại **response snapshot gốc** và không tạo thêm payment/audit/outbox record.
- Cùng key nhưng payload khác: `409 Conflict` với `code = IDEMPOTENCY_KEY_REUSED`.

Lỗi được trả về theo chuẩn Problem Details (RFC 9457), ví dụ:

```json
{
  "type": "https://paymentcore.dev/problems/idempotency-key-reused",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "Idempotency-Key 'checkout-0001' was already used with a different request payload.",
  "instance": "/api/v1/payments",
  "code": "IDEMPOTENCY_KEY_REUSED",
  "timestamp": "2026-11-08T01:00:00Z"
}
```

## Payment lifecycle

```text
INITIATED --authorize--> AUTHORIZED --capture--> CAPTURED --refund--> REFUNDED
    |                         |
    +---------cancel----------+--> CANCELLED
    |                         |
    +----------fail-----------+--> FAILED
```

| Action | Endpoint | Transition hợp lệ | Quy tắc |
| --- | --- | --- | --- |
| Authorize | `POST /{id}/authorize` | `INITIATED → AUTHORIZED` | Giữ tiền trên phương thức thanh toán |
| Capture | `POST /{id}/capture` | `AUTHORIZED → CAPTURED` | Chỉ capture sau khi authorize |
| Refund | `POST /{id}/refund` | `CAPTURED → REFUNDED` | Full refund trong iteration này |
| Cancel | `POST /{id}/cancel` | `INITIATED/AUTHORIZED → CANCELLED` | Payment đã capture phải refund, không cancel |
| Fail | `POST /{id}/fail` | `INITIATED/AUTHORIZED → FAILED` | Dùng để simulate processor/downstream failure |

Ví dụ chạy lifecycle:

```bash
curl -i -X POST http://localhost:8080/api/v1/payments/{id}/authorize
curl -i -X POST http://localhost:8080/api/v1/payments/{id}/capture
curl -i -X POST http://localhost:8080/api/v1/payments/{id}/refund
```

Các điểm quan trọng:

- Transition rule nằm trong một state machine duy nhất: `PaymentStateMachine` + `PaymentAction` + `Payment.apply(...)`.
- Transition không hợp lệ trả `409 Conflict` với `code = INVALID_PAYMENT_STATE`, kèm `currentStatus`, `action` và `targetStatus`.
- Transition dùng pessimistic row lock, nên hai request authorize đồng thời được xử lý tuần tự: một request thực hiện, request còn lại replay.
- Gọi lại đúng action khi payment đã ở trạng thái đích là idempotent: trả `200 OK`, `Transition-Outcome: REPLAYED`, không tạo thêm audit/outbox entry.
- Mỗi transition thật sự tạo một audit entry `PAYMENT_STATUS_CHANGED` và một outbox event trong cùng transaction.
- Payment ở trạng thái terminal (`REFUNDED`, `CANCELLED`, `FAILED`) không thể chuyển sang trạng thái khác, trừ việc gọi lại chính action đã đưa nó tới trạng thái đó.
- PostgreSQL enforce cả status domain và timestamp invariant của từng state.

## Idempotency được xử lý như thế nào

Idempotency có state record riêng trong bảng `idempotency_keys`:

```text
UNUSED
  └─ tạo payment + insert record + lưu response snapshot
       └─ STORED
            ├─ cùng request hash → REPLAY
            └─ khác request hash → CONFLICT (409)
```

1. `Idempotency-Key` được validate (8-128 ký tự, chỉ gồm chữ, số, `.`, `_`, `:`, `-`).
2. Payload được chuẩn hóa: amount về scale 2, currency về uppercase, trim merchant reference/description.
3. Fingerprint SHA-256 được tính từ payload đã chuẩn hóa.
4. Record trong `idempotency_keys` lưu request hash, payment id, response status và **response body snapshot**.
5. Unique constraint `uq_idempotency_keys_key` là lớp bảo vệ cuối cùng chống duplicate request.
6. Nếu hai request đồng thời cùng key, một transaction thắng; transaction còn lại rollback payment/audit/outbox và replay record đã có.
7. Nếu key đã tồn tại và fingerprint khác, API trả `409 Conflict` thay vì tạo giao dịch mới.

Replay trả đúng response snapshot tại thời điểm tạo, kể cả khi payment hiện tại đã `AUTHORIZED`, `CAPTURED` hoặc `REFUNDED`.

Các action lifecycle cũng idempotent theo trạng thái đích: gọi lại action khi payment đã ở trạng thái đó trả `200 OK` với `Transition-Outcome: REPLAYED`, không tạo audit/outbox mới.

## Audit và event-driven

- Mỗi payment tạo mới và mỗi transition thật sự đều ghi một bản ghi `payment_audit_logs` trong cùng transaction với payment.
- Mỗi mutation cũng ghi một `outbox_events` row trong cùng transaction:
  - `PENDING` → publish thành công → `PUBLISHED`
  - `PENDING` → lỗi và còn lượt retry → `PENDING` với `next_attempt_at`
  - `PENDING` → hết lượt retry → `FAILED`
- Sau commit, `OutboxImmediateDispatcher` thử publish ngay; `OutboxScheduler` retry các row `PENDING` còn lại.
- Publisher confirms ở chế độ `correlated`: mỗi lần gửi có `CorrelationData` riêng, `OutboxPublisher` chờ broker ack (tối đa `app.outbox.confirm-timeout`, default `5s`) rồi mới chuyển row sang `PUBLISHED`.
- Nack, confirm timeout, mất kết nối hoặc message bị broker trả lại (unroutable) đều ghi nhận một attempt thất bại: row giữ `PENDING`, tăng `attempts`, set `next_attempt_at`; scheduler retry tiếp, hết `OUTBOX_MAX_ATTEMPTS` mới thành `FAILED`.
- `spring.rabbitmq.publisher-returns=true` + mandatory flag bật để message không route được sẽ quay về publisher thay vì bị drop im lặng.
- RabbitMQ topology:
  - Exchange: `payment.events` (topic, durable)
  - `PaymentCreatedEvent` → queue `payment.created`, routing key `payment.created`
  - `PaymentStatusChangedEvent` → queue `payment.status-changed`, routing key `payment.status-changed`
- `PaymentStatusChangedEvent` có `fromStatus`/`toStatus` để downstream cập nhật ledger, notification hoặc analytics.
- `PUBLISHED` giờ có nghĩa broker đã ack message. Delivery vẫn là at-least-once: confirm về muộn sau khi timeout rồi retry có thể tạo bản trùng, nên consumer dedupe theo `messageId`/`eventId`. Header `outboxAttempts` cho biết đây là lần gửi thứ mấy.
- Chi tiết state machine: [docs/state-machines.md](docs/state-machines.md).

## Security

Mặc định local API mở để tiện test:

```yaml
app:
  security:
    mode: OPEN
```

`SecurityMode` là enum tường minh: `OPEN` hoặc `JWT`. Ở mode `OPEN`, ứng dụng log warning lúc startup.

Bật JWT/OAuth2 Resource Server bằng profile `jwt`:

```powershell
$env:SPRING_PROFILES_ACTIVE = "jwt"
$env:OAUTH2_ISSUER_URI = "https://your-identity-provider/realms/payment-sandbox"
.\mvnw.cmd spring-boot:run
```

Hoặc set trực tiếp:

```powershell
$env:SECURITY_MODE = "JWT"
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = "https://your-identity-provider/realms/payment-sandbox"
.\mvnw.cmd spring-boot:run
```

Khi bật, gửi `Authorization: Bearer <token>` cho các API payment; Swagger UI có nút Authorize. Health, Swagger và OpenAPI vẫn public.

## Cấu hình

Các biến môi trường chính (đều có default cho local):

| Biến | Default | Ý nghĩa |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | Port ứng dụng |
| `DB_URL` | `jdbc:postgresql://localhost:5432/payment_core` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `payment` / `payment` | Database credentials |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | `localhost` / `5672` | RabbitMQ |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | `payment` / `payment` | RabbitMQ credentials |
| `SECURITY_MODE` | `OPEN` | `OPEN` hoặc `JWT` |
| `SPRING_PROFILES_ACTIVE` | chưa đặt | Đặt `jwt` để bật cấu hình OAuth2 Resource Server |
| `OAUTH2_ISSUER_URI` | chưa đặt | OIDC issuer khi dùng profile `jwt` |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | chưa đặt | Cách cấu hình issuer chuẩn của Spring Boot |
| `OUTBOX_ENABLED` | `true` | Bật outbox retry scheduler |
| `OUTBOX_MAX_ATTEMPTS` | `5` | Số lần thử publish tối đa trước khi row thành `FAILED` |
| `OUTBOX_RETRY_DELAY` | `10s` | Delay giữa các lần retry |
| `OUTBOX_POLL_INTERVAL_MS` | `5000` | Chu kỳ scheduler quét outbox |
| `OUTBOX_CONFIRM_TIMEOUT` | `5s` | Thời gian tối đa chờ publisher confirm trước khi tính là publish lỗi |

## Test

```powershell
.\mvnw.cmd test
```

Integration test dùng Testcontainers để khởi động PostgreSQL, RabbitMQ và Redis thật. Yêu cầu Docker đang chạy; các image được pin version trong `TestcontainersConfiguration`.

Test hiện có:

- Context load.
- Tạo payment + outbox row `PUBLISHED` + RabbitMQ event.
- Replay cùng idempotency key với payload tương đương (`125.5` và `125.50`).
- Replay trả response snapshot gốc sau khi payment đã đổi trạng thái.
- Từ chối cùng key với payload khác.
- Phân biệt payload có ranh giới field khác nhau (chống fingerprint collision).
- Full lifecycle `authorize → capture → refund` + outbox/audit.
- Fail lifecycle `INITIATED/AUTHORIZED → FAILED` + capture sau fail bị `409`.
- Replay transition idempotent, không tạo outbox/audit trùng.
- Transition không hợp lệ trả `409 INVALID_PAYMENT_STATE` với `action`.
- Concurrent authorize requests được serialize và replay đúng.
- Audit trail.
- Validation và missing header.
- 404 cho payment không tồn tại.
- Publisher confirm: ack mới chuyển `PUBLISHED`; nack, timeout, mất kết nối và message unroutable đều fail.
- Broker tắt (`rabbitmqctl stop_app`): row giữ `PENDING`, `attempts` tăng, `last_error` được ghi; broker bật lại thì scheduler publish thành công và message tới queue thật.

## Cấu trúc package

```text
src/main/java/com/paymentcore
├── common
│   ├── api          # Problem Details, pagination response
│   └── config       # Security, OpenAPI
└── payment
    ├── api          # REST controller + DTO
    ├── domain       # Payment, PaymentAction, PaymentStateMachine, idempotency record, audit entity
    ├── event        # Created/status-changed event payloads, RabbitMQ topology
    ├── outbox       # Outbox entity/status machine/publisher/retry scheduler
    ├── repository   # Spring Data JPA repositories
    └── service      # Idempotency snapshot, transaction boundary, transitions, audit
```

Flyway migration nằm tại `src/main/resources/db/migration`.

## Docker image

```bash
docker build -t payment-core:local .
```

Dockerfile dùng multi-stage build (JDK 21 build stage, JRE 21 runtime), chạy bằng non-root user và có healthcheck gọi `/actuator/health`.

## Roadmap backend

1. DLQ, poison-message handling và metrics cho publisher confirms (nack/timeout rate).
2. Idempotency key TTL/cleanup và scope theo merchant.
3. Partial refunds, multiple refunds và compensation flows.
4. Redis cho rate limiting và cache idempotency.
5. JWT/OIDC thật, role-based access và merchant ownership.
6. Reconciliation job và báo cáo giao dịch lệch.
7. Observability: Grafana dashboards, tracing (OpenTelemetry), alert rules, outbox metrics.
8. CI/CD: Jenkinsfile/GitLab CI, build image, deploy Azure.
9. AI Payment Assistant với guardrails và giới hạn quyền truy cập dữ liệu.

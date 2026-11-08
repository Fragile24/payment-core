package com.paymentcore.ledger;

import com.paymentcore.TestcontainersConfiguration;
import com.paymentcore.payment.dto.CreatePaymentRequest;
import com.paymentcore.payment.domain.PaymentStatus;
import com.paymentcore.payment.event.MessagingProperties;
import com.paymentcore.payment.event.PaymentCreatedEvent;
import com.paymentcore.payment.event.PaymentStatusChangedEvent;
import com.paymentcore.payment.outbox.OutboxEvent;
import com.paymentcore.payment.outbox.OutboxEventRepository;
import com.paymentcore.payment.service.PaymentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = "app.reconciliation.enabled=false")
@Import(TestcontainersConfiguration.class)
class LedgerConsumerIntegrationTest {

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private OutboxEventRepository outboxEvents;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private MessagingProperties messaging;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private JsonMapper jsonMapper;

	@BeforeEach
	void cleanState() {
		amqpAdmin.purgeQueue(messaging.projectionQueue(), false);
		amqpAdmin.purgeQueue(messaging.projectionQueue() + ".dlq", false);
		amqpAdmin.purgeQueue(messaging.createdQueue(), false);
		amqpAdmin.purgeQueue(messaging.statusChangedQueue(), false);
		jdbc.update("DELETE FROM processed_events");
		jdbc.update("DELETE FROM payment_projection");
		jdbc.update("DELETE FROM payment_audit_logs");
		jdbc.update("DELETE FROM idempotency_keys");
		jdbc.update("DELETE FROM outbox_events");
		jdbc.update("DELETE FROM payments");
	}

	@Test
	void projectsCreatedAndStatusChangedEvents() {
		UUID paymentId = paymentService.create("ledger-create-0001", paymentRequest()).payment().id();

		awaitProjection(paymentId, "INITIATED", 0);

		paymentService.authorize(paymentId);

		awaitProjection(paymentId, "AUTHORIZED", 1);
	}

	@Test
	void ignoresDuplicateDeliveries() {
		UUID paymentId = paymentService.create("ledger-duplicate-0001", paymentRequest()).payment().id();
		awaitProjection(paymentId, "INITIATED", 0);

		OutboxEvent createdEvent = outboxEvents.findAll()
			.stream()
			.filter((event) -> event.getAggregateId().equals(paymentId))
			.findFirst()
			.orElseThrow();

		// Same delivery id as the original publish: the consumer must recognize it.
		publish(createdEvent.getExchange(), createdEvent.getRoutingKey(), createdEvent.getId(),
				createdEvent.getEventType(), createdEvent.getPayload());

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(meterRegistry
			.find("payment.projection.events")
			.tags("outcome", "duplicate", "eventType", "PAYMENT_CREATED")
			.counter()).isNotNull());

		assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class,
				createdEvent.getId()))
			.isEqualTo(1);
	}

	@Test
	void ignoresAnOlderEventThatArrivesAfterANewerOne() {
		UUID paymentId = UUID.randomUUID();
		PaymentStatusChangedEvent newer = new PaymentStatusChangedEvent(UUID.randomUUID(), paymentId,
				PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED, new BigDecimal("10.00"), "USD", 1,
				Instant.now());
		publish(messaging.exchange(), messaging.statusChangedRoutingKey(), UUID.randomUUID(),
				"PAYMENT_STATUS_CHANGED", jsonMapper.writeValueAsString(newer));
		awaitProjection(paymentId, "AUTHORIZED", 1);

		PaymentCreatedEvent older = new PaymentCreatedEvent(UUID.randomUUID(), paymentId, new BigDecimal("10.00"),
				"USD", null, PaymentStatus.INITIATED, 0, Instant.now());
		publish(messaging.exchange(), messaging.createdRoutingKey(), UUID.randomUUID(), "PAYMENT_CREATED",
				jsonMapper.writeValueAsString(older));

		// Give the consumer time to (incorrectly) apply the older event.
		await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).untilAsserted(
				() -> assertProjectionVersion(paymentId, "AUTHORIZED", 1));
	}

	@Test
	void deadLettersPoisonMessagesAfterRetries() {
		String poisonPayload = "{not-json";
		publish(messaging.exchange(), messaging.createdRoutingKey(), UUID.randomUUID(), "PAYMENT_CREATED",
				poisonPayload);

		Message deadLettered = rabbitTemplate.receive(messaging.projectionQueue() + ".dlq", 20_000);

		assertThat(deadLettered).isNotNull();
		assertThat(new String(deadLettered.getBody(), StandardCharsets.UTF_8)).isEqualTo(poisonPayload);
	}

	private void publish(String exchange, String routingKey, UUID deliveryId, String eventType, String payload) {
		Message message = MessageBuilder.withBody(payload.getBytes(StandardCharsets.UTF_8))
			.setContentType(MessageProperties.CONTENT_TYPE_JSON)
			.setMessageId(deliveryId.toString())
			.setHeader("eventType", eventType)
			.build();
		rabbitTemplate.send(exchange, routingKey, message);
	}

	private void awaitProjection(UUID paymentId, String status, long minimumVersion) {
		await().atMost(Duration.ofSeconds(15))
			.untilAsserted(() -> assertProjectionVersion(paymentId, status, minimumVersion));
	}

	private void assertProjectionVersion(UUID paymentId, String status, long minimumVersion) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"SELECT status, version FROM payment_projection WHERE payment_id = ?", paymentId);
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("status")).isEqualTo(status);
		assertThat(((Number) rows.get(0).get("version")).longValue()).isGreaterThanOrEqualTo(minimumVersion);
	}

	private CreatePaymentRequest paymentRequest() {
		return new CreatePaymentRequest(new BigDecimal("10.00"), "USD", "ORDER-LEDGER-0001", "Ledger projection");
	}

}

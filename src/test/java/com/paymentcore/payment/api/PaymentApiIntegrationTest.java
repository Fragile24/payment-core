package com.paymentcore.payment.api;

import com.jayway.jsonpath.JsonPath;
import com.paymentcore.TestcontainersConfiguration;
import com.paymentcore.payment.event.MessagingProperties;
import com.paymentcore.payment.outbox.OutboxEvent;
import com.paymentcore.payment.outbox.OutboxEventRepository;
import com.paymentcore.payment.outbox.OutboxStatus;
import com.paymentcore.payment.repository.IdempotencyRecordRepository;
import com.paymentcore.payment.repository.PaymentAuditLogRepository;
import com.paymentcore.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.reconciliation.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentApiIntegrationTest {

	private static final String CREATE_PAYMENT_BODY = """
			{
			  "amount": 125.50,
			  "currency": "usd",
			  "merchantReference": " ORDER-2026-0001 ",
			  "description": " Sandbox order "
			}
			""";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PaymentRepository payments;

	@Autowired
	private PaymentAuditLogRepository auditLogs;

	@Autowired
	private IdempotencyRecordRepository idempotencyRecords;

	@Autowired
	private OutboxEventRepository outboxEvents;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private MessagingProperties messagingProperties;

	@BeforeEach
	void cleanState() {
		outboxEvents.deleteAll();
		idempotencyRecords.deleteAll();
		auditLogs.deleteAll();
		payments.deleteAll();
		amqpAdmin.initialize();
		amqpAdmin.purgeQueue(messagingProperties.createdQueue(), false);
		amqpAdmin.purgeQueue(messagingProperties.statusChangedQueue(), false);
	}

	@Test
	void createsPaymentPublishesEventAndReplaysSameIdempotencyKey() throws Exception {
		MvcResult created = mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_PAYMENT_BODY))
			.andExpect(status().isCreated())
			.andExpect(header().string("Idempotent-Replayed", "false"))
			.andExpect(header().string("Idempotency-Outcome", "CREATED"))
			.andExpect(header().exists("Location"))
			.andExpect(jsonPath("$.status").value("INITIATED"))
			.andExpect(jsonPath("$.amount").value(125.50))
			.andExpect(jsonPath("$.currency").value("USD"))
			.andExpect(jsonPath("$.merchantReference").value("ORDER-2026-0001"))
			.andExpect(jsonPath("$.description").value("Sandbox order"))
			.andReturn();

		String paymentId = JsonPath.parse(created.getResponse().getContentAsString()).read("$.id", String.class);

		Message event = rabbitTemplate.receive(messagingProperties.createdQueue(), 5000);
		assertThat(event).isNotNull();
		String eventPayload = new String(event.getBody(), StandardCharsets.UTF_8);
		assertThat(eventPayload).contains(paymentId).contains("\"status\":\"INITIATED\"");

		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 125.5, "currency": "USD", "merchantReference": "ORDER-2026-0001",
						 "description": "Sandbox order"}
						"""))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(header().string("Idempotency-Outcome", "REPLAYED"))
			.andExpect(jsonPath("$.id").value(paymentId));

		assertThat(payments.count()).isEqualTo(1);
		assertThat(auditLogs.count()).isEqualTo(1);
		assertThat(idempotencyRecords.count()).isEqualTo(1);
		assertThat(outboxEvents.count()).isEqualTo(1);
		assertThat(outboxEvents.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
	}

	@Test
	void rejectsSameIdempotencyKeyWithDifferentPayload() throws Exception {
		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_PAYMENT_BODY))
			.andExpect(status().isCreated());

		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 999.00, "currency": "USD", "merchantReference": "ORDER-2026-0001",
						 "description": "Sandbox order"}
						"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(payments.count()).isEqualTo(1);
	}

	@Test
	void distinguishesPayloadsThatDifferByFieldBoundaries() throws Exception {
		String firstPayload = "{\"amount\":10.00,\"currency\":\"USD\",\"merchantReference\":\"a\\nb\","
				+ "\"description\":\"c\"}";
		String secondPayload = "{\"amount\":10.00,\"currency\":\"USD\",\"merchantReference\":\"a\","
				+ "\"description\":\"b\\nc\"}";

		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0005")
				.contentType(MediaType.APPLICATION_JSON)
				.content(firstPayload))
			.andExpect(status().isCreated());

		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0005")
				.contentType(MediaType.APPLICATION_JSON)
				.content(secondPayload))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void completesFullLifecycleAndPublishesStatusEvents() throws Exception {
		String paymentId = createPayment("checkout-lifecycle-0001");

		mockMvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "false"))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"))
			.andExpect(jsonPath("$.authorizedAt").exists());

		Message authorizedEvent = rabbitTemplate.receive(messagingProperties.statusChangedQueue(), 5000);
		assertThat(authorizedEvent).isNotNull();
		String authorizedPayload = new String(authorizedEvent.getBody(), StandardCharsets.UTF_8);
		assertThat(authorizedPayload).contains("\"fromStatus\":\"INITIATED\"")
			.contains("\"toStatus\":\"AUTHORIZED\"");

		mockMvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.status").value("AUTHORIZED"));
		assertThat(rabbitTemplate.receive(messagingProperties.statusChangedQueue(), 300)).isNull();

		mockMvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "false"))
			.andExpect(jsonPath("$.status").value("CAPTURED"))
			.andExpect(jsonPath("$.capturedAt").exists());

		mockMvc.perform(post("/api/v1/payments/{paymentId}/refund", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REFUNDED"))
			.andExpect(jsonPath("$.refundedAt").exists());

		mockMvc.perform(get("/api/v1/payments/{paymentId}/audit", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(4))
			.andExpect(jsonPath("$[0].eventType").value("PAYMENT_CREATED"))
			.andExpect(jsonPath("$[1].eventType").value("PAYMENT_STATUS_CHANGED"))
			.andExpect(jsonPath("$[1].fromStatus").value("INITIATED"))
			.andExpect(jsonPath("$[1].toStatus").value("AUTHORIZED"))
			.andExpect(jsonPath("$[2].toStatus").value("CAPTURED"))
			.andExpect(jsonPath("$[3].toStatus").value("REFUNDED"));

		assertAllOutboxPublished(4);
	}

	@Test
	void rejectsInvalidLifecycleTransitions() throws Exception {
		String paymentId = createPayment("checkout-lifecycle-0002");

		mockMvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
			.andExpect(jsonPath("$.currentStatus").value("INITIATED"))
			.andExpect(jsonPath("$.targetStatus").value("CAPTURED"));

		mockMvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId))
			.andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/payments/{paymentId}/cancel", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"))
			.andExpect(jsonPath("$.cancelledAt").exists());

		mockMvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
			.andExpect(jsonPath("$.currentStatus").value("CANCELLED"))
			.andExpect(jsonPath("$.targetStatus").value("CAPTURED"));

		assertThat(auditLogs.count()).isEqualTo(3);
		assertAllOutboxPublished(3);
	}

	@Test
	void failsPaymentAndCannotCaptureAfterFailure() throws Exception {
		String paymentId = createPayment("checkout-lifecycle-fail");

		mockMvc.perform(post("/api/v1/payments/{paymentId}/fail", paymentId))
			.andExpect(status().isOk())
			.andExpect(header().string("Transition-Outcome", "APPLIED"))
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.failedAt").exists());

		mockMvc.perform(post("/api/v1/payments/{paymentId}/capture", paymentId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_PAYMENT_STATE"))
			.andExpect(jsonPath("$.currentStatus").value("FAILED"))
			.andExpect(jsonPath("$.action").value("CAPTURE"));

		assertThat(auditLogs.count()).isEqualTo(2);
		assertAllOutboxPublished(2);
	}

	@Test
	void replaysOriginalCreationSnapshotAfterLifecycleChanges() throws Exception {
		String idempotencyKey = "checkout-snapshot-0001";
		String paymentId = createPayment(idempotencyKey);

		mockMvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"));

		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_PAYMENT_BODY))
			.andExpect(status().isOk())
			.andExpect(header().string("Idempotent-Replayed", "true"))
			.andExpect(jsonPath("$.id").value(paymentId))
			.andExpect(jsonPath("$.status").value("INITIATED"));

		mockMvc.perform(get("/api/v1/payments/{paymentId}", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("AUTHORIZED"));

		assertThat(idempotencyRecords.count()).isEqualTo(1);
		assertAllOutboxPublished(2);
	}

	@Test
	void handlesConcurrentAuthorizeRequestsIdempotently() throws Exception {
		String paymentId = createPayment("checkout-lifecycle-concurrent");
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Callable<MvcResult> authorize = () -> {
				start.await();
				return mockMvc.perform(post("/api/v1/payments/{paymentId}/authorize", paymentId)).andReturn();
			};
			Future<MvcResult> first = executor.submit(authorize);
			Future<MvcResult> second = executor.submit(authorize);
			start.countDown();

			MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(10, TimeUnit.SECONDS);

			assertThat(List.of(firstResult.getResponse().getStatus(), secondResult.getResponse().getStatus()))
				.containsOnly(200);
			assertThat(List.of(firstResult.getResponse().getHeader("Idempotent-Replayed"),
					secondResult.getResponse().getHeader("Idempotent-Replayed")))
				.containsExactlyInAnyOrder("false", "true");
			assertThat(auditLogs.count()).isEqualTo(2);
			assertAllOutboxPublished(2);
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void returnsPaymentAuditTrail() throws Exception {
		MvcResult created = mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0003")
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_PAYMENT_BODY))
			.andExpect(status().isCreated())
			.andReturn();

		String paymentId = JsonPath.parse(created.getResponse().getContentAsString()).read("$.id", String.class);

		mockMvc.perform(get("/api/v1/payments/{paymentId}/audit", paymentId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].eventType").value("PAYMENT_CREATED"))
			.andExpect(jsonPath("$[0].toStatus").value("INITIATED"));
	}

	@Test
	void validatesRequestAndRequiredIdempotencyHeader() throws Exception {
		mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", "checkout-0004")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 0, "currency": "USD"}
						"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.amount").exists());

		mockMvc
			.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 10.00, "currency": "USD"}
						"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MISSING_HEADER"));
	}

	@Test
	void returnsNotFoundForUnknownPayment() throws Exception {
		mockMvc.perform(get("/api/v1/payments/{paymentId}", "00000000-0000-0000-0000-000000000000"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
	}

	private String createPayment(String idempotencyKey) throws Exception {
		MvcResult created = mockMvc
			.perform(post("/api/v1/payments").header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_PAYMENT_BODY))
			.andExpect(status().isCreated())
			.andReturn();
		return JsonPath.parse(created.getResponse().getContentAsString()).read("$.id", String.class);
	}

	private void assertAllOutboxPublished(int expectedCount) {
		assertThat(outboxEvents.count()).isEqualTo(expectedCount);
		assertThat(outboxEvents.findAll()).extracting(OutboxEvent::getStatus)
			.containsOnly(OutboxStatus.PUBLISHED);
	}

}

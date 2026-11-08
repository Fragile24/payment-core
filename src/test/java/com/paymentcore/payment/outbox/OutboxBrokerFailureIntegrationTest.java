package com.paymentcore.payment.outbox;

import com.paymentcore.TestcontainersConfiguration;
import com.paymentcore.payment.event.MessagingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Exercises the failure half of the outbox: the row must stay pending while the
 * broker cannot confirm, and a later retry must publish it once the broker is
 * back.
 *
 * <p>The broker is made unavailable with {@code rabbitmqctl stop_app}. The
 * container keeps running, so the mapped port and the durable queues stay the
 * same; that makes the outage deterministic without recreating the container or
 * re-pointing the application at a new port.</p>
 *
 * <p>The scheduler is effectively disabled for this test class (one hour poll
 * interval) so the test drives {@link OutboxScheduler#dispatchPending()}
 * explicitly instead of racing the background poll.</p>
 */
@SpringBootTest(properties = { "app.outbox.poll-interval-ms=3600000", "app.outbox.retry-delay=1s",
		"app.outbox.confirm-timeout=2s" })
@Import(TestcontainersConfiguration.class)
class OutboxBrokerFailureIntegrationTest {

	private static final Duration BROKER_TIMEOUT = Duration.ofSeconds(60);

	@Autowired
	private OutboxEventRepository outboxEvents;

	@Autowired
	private OutboxPublisher outboxPublisher;

	@Autowired
	private OutboxScheduler outboxScheduler;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private AmqpAdmin amqpAdmin;

	@Autowired
	private MessagingProperties messaging;

	@Autowired
	private RabbitMQContainer rabbitmq;

	@BeforeEach
	void cleanState() {
		outboxEvents.deleteAll();
		amqpAdmin.purgeQueue(messaging.createdQueue(), false);
		amqpAdmin.purgeQueue(messaging.statusChangedQueue(), false);
	}

	@AfterEach
	void restartBrokerIfAStoppedTestLeftItDown() throws Exception {
		if (!isBrokerApplicationUp()) {
			startBrokerApplication();
		}
	}

	@Test
	void recordsFailureWhileBrokerIsDownThenPublishesOnRetryAfterRecovery() throws Exception {
		UUID outboxId = outboxEvents.save(pendingEvent(messaging.createdRoutingKey())).getId();

		stopBrokerApplication();
		try {
			outboxPublisher.publish(outboxId);

			OutboxEvent failedAttempt = reload(outboxId);
			assertThat(failedAttempt.getStatus()).isEqualTo(OutboxStatus.PENDING);
			assertThat(failedAttempt.getAttempts()).isEqualTo(1);
			assertThat(failedAttempt.getLastError()).isNotBlank();
			assertThat(failedAttempt.getNextAttemptAt()).isNotNull();
			assertThat(failedAttempt.getPublishedAt()).isNull();
		}
		finally {
			startBrokerApplication();
		}

		// Recovery path: the row is old enough now, so the scheduler must be able
		// to publish it once the broker confirms again.
		outboxPublisher.retryFailed(outboxId);
		outboxScheduler.dispatchPending();

		OutboxEvent published = reload(outboxId);
		assertThat(published.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(published.getPublishedAt()).isNotNull();
		assertThat(published.getAttempts()).isEqualTo(1);

		Message message = rabbitTemplate.receive(messaging.createdQueue(), 10_000);
		assertThat(message).isNotNull();
		assertThat(message.getMessageProperties().getMessageId()).isEqualTo(outboxId.toString());
		Object attemptsHeader = message.getMessageProperties().getHeader("outboxAttempts");
		assertThat(attemptsHeader).isEqualTo(1);
	}

	@Test
	void recordsFailureWhenBrokerReturnsMessageInsteadOfRoutingIt() {
		UUID outboxId = outboxEvents.save(pendingEvent("payment.unroutable")).getId();

		outboxPublisher.publish(outboxId);

		OutboxEvent event = reload(outboxId);
		assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(event.getAttempts()).isEqualTo(1);
		assertThat(event.getLastError()).containsIgnoringCase("unroutable");
		assertThat(event.getPublishedAt()).isNull();
	}

	private OutboxEvent reload(UUID outboxId) {
		return outboxEvents.findById(outboxId).orElseThrow();
	}

	private OutboxEvent pendingEvent(String routingKey) {
		return OutboxEvent.pending("PAYMENT", UUID.randomUUID(), "PAYMENT_CREATED", messaging.exchange(), routingKey,
				"{\"paymentId\":\"" + UUID.randomUUID() + "\",\"status\":\"INITIATED\"}");
	}

	private void stopBrokerApplication() throws Exception {
		ExecResult result = rabbitmq.execInContainer("rabbitmqctl", "stop_app");
		assertThat(result.getExitCode()).as("rabbitmqctl stop_app: %s", result.getStderr()).isZero();
		await("broker stops accepting publishes", () -> !isBrokerApplicationUp());
	}

	private void startBrokerApplication() throws Exception {
		ExecResult result = rabbitmq.execInContainer("rabbitmqctl", "start_app");
		assertThat(result.getExitCode()).as("rabbitmqctl start_app: %s", result.getStderr()).isZero();
		await("broker accepts connections again", this::isBrokerApplicationUp);
	}

	private boolean isBrokerApplicationUp() {
		try {
			return Boolean.TRUE.equals(rabbitTemplate.execute(channel -> channel.isOpen()));
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	private void await(String description, BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + BROKER_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(500);
		}
		fail("Timed out after %s waiting until %s".formatted(BROKER_TIMEOUT, description));
	}

}

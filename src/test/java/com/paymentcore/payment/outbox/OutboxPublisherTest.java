package com.paymentcore.payment.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

	private OutboxEventRepository outboxEvents;

	private ConfirmedMessagePublisher messagePublisher;

	private SimpleMeterRegistry meterRegistry;

	private OutboxPublisher publisher;

	@BeforeEach
	void setUp() {
		this.outboxEvents = mock(OutboxEventRepository.class);
		this.messagePublisher = mock(ConfirmedMessagePublisher.class);
		this.meterRegistry = new SimpleMeterRegistry();
		OutboxProperties properties = new OutboxProperties(3, Duration.ofSeconds(10), Duration.ofSeconds(2), 100);
		this.publisher = new OutboxPublisher(this.outboxEvents, this.messagePublisher, properties,
				new OutboxPublishMetrics(this.meterRegistry));
	}

	@Test
	void marksPublishedAfterBrokerConfirm() {
		OutboxEvent event = pendingEvent();
		when(outboxEvents.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));

		publisher.publish(event.getId());

		verify(messagePublisher).publish(event);
		assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(event.getPublishedAt()).isNotNull();
		assertThat(event.getAttempts()).isZero();
		assertThat(meterRegistry.get("outbox.publish").tag("result", "confirmed").counter().count()).isEqualTo(1);
	}

	@Test
	void recordsFailureAndSchedulesRetryWhenBrokerDoesNotConfirm() {
		OutboxEvent event = pendingEvent();
		when(outboxEvents.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		doThrow(new OutboxPublishException(OutboxPublishException.Reason.TIMEOUT,
				"publisher confirm timeout after 2s outboxId=" + event.getId())).when(messagePublisher)
			.publish(event);

		publisher.publish(event.getId());

		assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(event.getAttempts()).isEqualTo(1);
		assertThat(event.getLastError()).contains("confirm timeout");
		assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
		assertThat(event.getPublishedAt()).isNull();
		assertThat(meterRegistry.get("outbox.publish")
			.tag("result", "failed")
			.tag("reason", "timeout")
			.counter()
			.count()).isEqualTo(1);
	}

	@Test
	void reportsConnectionFailuresWithTheirOwnReason() {
		OutboxEvent event = pendingEvent();
		when(outboxEvents.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		doThrow(new OutboxPublishException(OutboxPublishException.Reason.CONNECTION,
				"could not send outboxId=%s: connection refused".formatted(event.getId()),
				new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("refused"))))
			.when(messagePublisher)
			.publish(event);

		publisher.publish(event.getId());

		assertThat(meterRegistry.get("outbox.publish")
			.tag("result", "failed")
			.tag("reason", "connection")
			.counter()
			.count()).isEqualTo(1);
	}

	@Test
	void givesUpAfterMaxAttempts() {
		OutboxEvent event = pendingEvent();
		when(outboxEvents.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));
		doThrow(new OutboxPublishException(OutboxPublishException.Reason.NACK, "broker nacked message"))
			.when(messagePublisher)
			.publish(event);

		for (int attempt = 1; attempt <= 3; attempt++) {
			// Make the row due again, as the retry scheduler would.
			if (attempt > 1) {
				event.retryNow();
			}
			publisher.publish(event.getId());
		}

		assertThat(event.getAttempts()).isEqualTo(3);
		assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
		assertThat(event.getNextAttemptAt()).isNull();
	}

	@Test
	void skipsRowsThatAreNotPublishable() {
		OutboxEvent event = pendingEvent();
		event.markPublished();
		when(outboxEvents.findByIdForUpdate(event.getId())).thenReturn(Optional.of(event));

		publisher.publish(event.getId());

		verifyNoInteractions(messagePublisher);
	}

	private OutboxEvent pendingEvent() {
		OutboxEvent event = OutboxEvent.pending("PAYMENT", UUID.randomUUID(), "PAYMENT_CREATED",
				"payment.events", "payment.created", "{}");
		ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
		return event;
	}

}

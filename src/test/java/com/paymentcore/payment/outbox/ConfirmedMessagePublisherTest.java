package com.paymentcore.payment.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ConfirmedMessagePublisherTest {

	private RabbitTemplate rabbitTemplate;

	private ConfirmedMessagePublisher publisher;

	private AtomicReference<CorrelationData> sentCorrelation;

	@BeforeEach
	void setUp() {
		this.rabbitTemplate = mock(RabbitTemplate.class);
		this.sentCorrelation = new AtomicReference<>();
		// Short confirm timeout so the timeout case stays fast.
		OutboxProperties properties = new OutboxProperties(true, 3, Duration.ofSeconds(10), 5000,
				Duration.ofMillis(150), 100);
		this.publisher = new ConfirmedMessagePublisher(this.rabbitTemplate, properties);
	}

	@Test
	void completesOnlyAfterBrokerAck() {
		OutboxEvent event = pendingEvent();
		stubSend((correlationData) -> correlationData.getFuture()
			.complete(new CorrelationData.Confirm(true, null)));

		publisher.publish(event);

		ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
		verify(rabbitTemplate).send(anyString(), anyString(), message.capture(), any(CorrelationData.class));
		assertThat(message.getValue().getMessageProperties().getMessageId()).isEqualTo(event.getId().toString());
		assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8))
			.isEqualTo(event.getPayload());
		assertThat(sentCorrelation.get().getId()).startsWith(event.getId().toString());
	}

	@Test
	void failsWhenBrokerNacks() {
		stubSend((correlationData) -> correlationData.getFuture()
			.complete(new CorrelationData.Confirm(false, "disk full")));

		assertThatThrownBy(() -> publisher.publish(pendingEvent()))
			.isInstanceOfSatisfying(OutboxPublishException.class, (ex) -> {
				assertThat(ex.getReason()).isEqualTo(OutboxPublishException.Reason.NACK);
				assertThat(ex).hasMessageContaining("nacked").hasMessageContaining("disk full");
			});
	}

	@Test
	void failsWhenConfirmNeverArrives() {
		stubSend((correlationData) -> {
			// Simulates a broker that accepted the frame but never confirms it.
		});

		assertThatThrownBy(() -> publisher.publish(pendingEvent()))
			.isInstanceOfSatisfying(OutboxPublishException.class, (ex) -> {
				assertThat(ex.getReason()).isEqualTo(OutboxPublishException.Reason.TIMEOUT);
				assertThat(ex).hasMessageContaining("confirm timeout");
			});
	}

	@Test
	void failsWhenMessageIsReturnedAsUnroutableEvenThoughBrokerAcked() {
		stubSend((correlationData) -> {
			correlationData.setReturned(new ReturnedMessage(new Message(new byte[0]), 312,
					"NO_ROUTE", "payment.events", "payment.unknown"));
			correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
		});

		assertThatThrownBy(() -> publisher.publish(pendingEvent()))
			.isInstanceOfSatisfying(OutboxPublishException.class, (ex) -> {
				assertThat(ex.getReason()).isEqualTo(OutboxPublishException.Reason.RETURNED);
				assertThat(ex).hasMessageContaining("unroutable").hasMessageContaining("312");
			});
	}

	@Test
	void propagatesConnectionFailure() {
		doAnswer((invocation) -> {
			throw new AmqpException("connection refused");
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

		assertThatThrownBy(() -> publisher.publish(pendingEvent()))
			.isInstanceOf(AmqpException.class)
			.hasMessageContaining("connection refused");
	}

	private void stubSend(Consumer<CorrelationData> afterSend) {
		doAnswer((invocation) -> {
			CorrelationData correlationData = invocation.getArgument(3);
			sentCorrelation.set(correlationData);
			afterSend.accept(correlationData);
			return null;
		}).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
	}

	private OutboxEvent pendingEvent() {
		OutboxEvent event = OutboxEvent.pending("PAYMENT", UUID.randomUUID(), "PAYMENT_CREATED",
				"payment.events", "payment.created", "{\"paymentId\":\"" + UUID.randomUUID() + "\"}");
		ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
		return event;
	}

}

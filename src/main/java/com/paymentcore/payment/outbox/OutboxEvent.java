package com.paymentcore.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "aggregate_type", nullable = false, length = 64)
	private String aggregateType;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@Column(name = "event_type", nullable = false, length = 64)
	private String eventType;

	@Column(name = "exchange", nullable = false, length = 255)
	private String exchange;

	@Column(name = "routing_key", nullable = false, length = 255)
	private String routingKey;

	@Column(name = "payload", nullable = false, columnDefinition = "text")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private OutboxStatus status;

	@Column(name = "attempts", nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "last_error", columnDefinition = "text")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OutboxEvent() {
		// Required by JPA.
	}

	public static OutboxEvent pending(String aggregateType, UUID aggregateId, String eventType, String exchange,
			String routingKey, String payload) {
		OutboxEvent event = new OutboxEvent();
		event.aggregateType = aggregateType;
		event.aggregateId = aggregateId;
		event.eventType = eventType;
		event.exchange = exchange;
		event.routingKey = routingKey;
		event.payload = payload;
		event.status = OutboxStatus.PENDING;
		event.attempts = 0;
		event.createdAt = Instant.now();
		event.nextAttemptAt = event.createdAt;
		return event;
	}

	public boolean isPublishableNow() {
		return status == OutboxStatus.PENDING
				&& (nextAttemptAt == null || !nextAttemptAt.isAfter(Instant.now()));
	}

	public void markPublished() {
		requireTransition(OutboxStatus.PUBLISHED);
		this.status = OutboxStatus.PUBLISHED;
		this.publishedAt = Instant.now();
		this.nextAttemptAt = null;
		this.lastError = null;
	}

	public void recordFailure(String error, int maxAttempts, Duration retryDelay) {
		requireTransition(OutboxStatus.PENDING);
		this.attempts++;
		this.lastError = error;
		OutboxStatus nextStatus = OutboxStateMachine.afterFailure(this.attempts, maxAttempts);
		requireTransition(nextStatus);
		this.status = nextStatus;
		this.nextAttemptAt = nextStatus == OutboxStatus.PENDING ? Instant.now().plus(retryDelay) : null;
	}

	/**
	 * Makes the row due again for the next publish attempt without resetting the
	 * attempt count, so a row that is still broken fails fast instead of looping.
	 * Giving a {@code FAILED} row a fresh budget is the operator replay in the
	 * runbook, not this method.
	 */
	public void retryNow() {
		requireTransition(OutboxStatus.PENDING);
		this.status = OutboxStatus.PENDING;
		this.nextAttemptAt = Instant.now();
	}

	private void requireTransition(OutboxStatus targetStatus) {
		if (!OutboxStateMachine.canTransition(this.status, targetStatus)) {
			throw new OutboxStateException(this.status, targetStatus);
		}
	}

	public UUID getId() {
		return id;
	}

	public String getAggregateType() {
		return aggregateType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getExchange() {
		return exchange;
	}

	public String getRoutingKey() {
		return routingKey;
	}

	public String getPayload() {
		return payload;
	}

	public OutboxStatus getStatus() {
		return status;
	}

	public int getAttempts() {
		return attempts;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public long getVersion() {
		return version;
	}

}

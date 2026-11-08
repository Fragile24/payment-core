package com.paymentcore.payment.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxHealthIndicatorTest {

	private final OutboxEventRepository outboxEvents = mock(OutboxEventRepository.class);

	@Test
	void reportsTheBacklogWithoutFailingWhilePendingRowsWaitForRetry() {
		when(outboxEvents.countByStatus(OutboxStatus.PENDING)).thenReturn(12L);
		when(outboxEvents.countByStatus(OutboxStatus.FAILED)).thenReturn(2L);

		Health health = new OutboxHealthIndicator(outboxEvents, properties(100)).health();

		assertThat(health.getStatus()).isEqualTo(Status.UP);
		assertThat(health.getDetails()).containsEntry("pending", 12L).containsEntry("failed", 2L);
	}

	@Test
	void goesDownWhenExhaustedEventsExceedTheThreshold() {
		when(outboxEvents.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);
		when(outboxEvents.countByStatus(OutboxStatus.FAILED)).thenReturn(6L);

		Health health = new OutboxHealthIndicator(outboxEvents, properties(5)).health();

		assertThat(health.getStatus()).isEqualTo(Status.DOWN);
		assertThat(health.getDetails()).containsEntry("failed", 6L);
	}

	private OutboxProperties properties(long failedHealthThreshold) {
		return new OutboxProperties(5, Duration.ofSeconds(10), Duration.ofSeconds(5), failedHealthThreshold);
	}

}

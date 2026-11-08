package com.paymentcore.payment.outbox;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces the outbox backlog through {@code /actuator/health}.
 *
 * <p>A healthy payments service can still be silently failing to deliver events:
 * the payment API keeps returning 201 while outbox rows accumulate. Reporting
 * the backlog as a health contributor makes that visible to orchestrators and
 * alerting. The indicator is DOWN only when exhausted ({@code FAILED}) rows
 * exceed the configured threshold; pending rows are expected during a retry
 * window and are reported as detail, not as failure.</p>
 *
 * <p>The two counts are cheap indexed queries (the outbox table has an index on
 * {@code status}); they run only when the health endpoint is scraped.</p>
 */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {

	private final OutboxEventRepository outboxEvents;

	private final OutboxProperties properties;

	public OutboxHealthIndicator(OutboxEventRepository outboxEvents, OutboxProperties properties) {
		this.outboxEvents = outboxEvents;
		this.properties = properties;
	}

	@Override
	public Health health() {
		long pending = outboxEvents.countByStatus(OutboxStatus.PENDING);
		long failed = outboxEvents.countByStatus(OutboxStatus.FAILED);
		Health.Builder builder = failed > properties.failedHealthThreshold() ? Health.down() : Health.up();
		return builder.withDetail("pending", pending)
			.withDetail("failed", failed)
			.withDetail("failedAlertThreshold", properties.failedHealthThreshold())
			.build();
	}

}

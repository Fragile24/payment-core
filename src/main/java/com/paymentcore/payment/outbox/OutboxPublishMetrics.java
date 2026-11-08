package com.paymentcore.payment.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Outbox delivery metrics.
 *
 * <p>{@code outbox.publish} counts attempts by result and failure reason, while
 * {@code outbox.publish.duration} measures how long the broker hand-off takes
 * (including the publisher confirm wait). Alerting on a rising failure ratio or
 * on p99 latency near {@code app.outbox.confirm-timeout} catches broker
 * problems before rows pile up.</p>
 */
@Component
public class OutboxPublishMetrics {

	static final String PUBLISH_ATTEMPTS = "outbox.publish";

	static final String PUBLISH_DURATION = "outbox.publish.duration";

	private final MeterRegistry meterRegistry;

	public OutboxPublishMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void recordConfirmed(Duration duration) {
		meterRegistry.counter(PUBLISH_ATTEMPTS, "result", "confirmed").increment();
		meterRegistry.timer(PUBLISH_DURATION, "result", "confirmed").record(duration);
	}

	public void recordFailed(String reason, Duration duration) {
		meterRegistry.counter(PUBLISH_ATTEMPTS, "result", "failed", "reason", reason).increment();
		meterRegistry.timer(PUBLISH_DURATION, "result", "failed", "reason", reason).record(duration);
	}

}

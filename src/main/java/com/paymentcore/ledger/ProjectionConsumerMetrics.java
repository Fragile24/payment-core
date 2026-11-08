package com.paymentcore.ledger;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Consumer metrics.
 *
 * <p>{@code outcome=duplicate} should be non-zero in any real system (at-least-once
 * delivery); a sudden rise usually means a consumer restart storm. A non-zero
 * {@code outcome=failed} is the signal that matters: it means the retry budget
 * is being consumed, and the messages will end up in {@code payment.projection.dlq}
 * if it continues.</p>
 */
@Component
public class ProjectionConsumerMetrics {

	static final String EVENTS = "payment.projection.events";

	private final MeterRegistry meterRegistry;

	public ProjectionConsumerMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void record(ProjectionOutcome outcome, String eventType) {
		meterRegistry.counter(EVENTS, "outcome", outcome.name().toLowerCase(Locale.ROOT), "eventType", eventType)
			.increment();
	}

	public void recordFailed(String eventType) {
		meterRegistry.counter(EVENTS, "outcome", "failed", "eventType", eventType).increment();
	}

}

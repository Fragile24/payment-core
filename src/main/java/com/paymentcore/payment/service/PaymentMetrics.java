package com.paymentcore.payment.service;

import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.domain.PaymentTransitionOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business counters for payments.
 *
 * <p>Tags are deliberately low-cardinality enums: never tag a metric with a
 * payment id, a merchant reference or an idempotency key, otherwise the time
 * series count explodes. Per-entity debugging belongs in logs and traces, not
 * in metrics.</p>
 */
@Component
public class PaymentMetrics {

	static final String CREATED = "payment.created";

	static final String TRANSITION = "payment.transition";

	private final MeterRegistry meterRegistry;

	public PaymentMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void recordCreated(PaymentCreationOutcome outcome) {
		meterRegistry.counter(CREATED, "outcome", outcome.name()).increment();
	}

	public void recordTransition(PaymentAction action, PaymentTransitionOutcome outcome) {
		meterRegistry.counter(TRANSITION, "action", action.name(), "outcome", outcome.name()).increment();
	}

}

package com.paymentcore.payment.service;

import com.paymentcore.payment.domain.PaymentAction;
import com.paymentcore.payment.domain.PaymentTransitionOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMetricsTest {

	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

	private final PaymentMetrics metrics = new PaymentMetrics(meterRegistry);

	@Test
	void countsCreationOutcomes() {
		metrics.recordCreated(PaymentCreationOutcome.CREATED);
		metrics.recordCreated(PaymentCreationOutcome.REPLAYED);
		metrics.recordCreated(PaymentCreationOutcome.REPLAYED);

		assertThat(meterRegistry.get("payment.created").tag("outcome", "CREATED").counter().count()).isEqualTo(1);
		assertThat(meterRegistry.get("payment.created").tag("outcome", "REPLAYED").counter().count()).isEqualTo(2);
	}

	@Test
	void countsTransitionsByActionAndOutcome() {
		metrics.recordTransition(PaymentAction.AUTHORIZE, PaymentTransitionOutcome.APPLIED);
		metrics.recordTransition(PaymentAction.AUTHORIZE, PaymentTransitionOutcome.REPLAYED);

		assertThat(meterRegistry.get("payment.transition")
			.tag("action", "AUTHORIZE")
			.tag("outcome", "APPLIED")
			.counter()
			.count()).isEqualTo(1);
		assertThat(meterRegistry.get("payment.transition")
			.tag("action", "AUTHORIZE")
			.tag("outcome", "REPLAYED")
			.counter()
			.count()).isEqualTo(1);
	}

}

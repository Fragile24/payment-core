package com.paymentcore.payment.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLifecycleTest {

	private static final Map<PaymentStatus, Function<Payment, Instant>> LIFECYCLE_TIMESTAMPS = Map.of(
			PaymentStatus.AUTHORIZED, Payment::getAuthorizedAt, PaymentStatus.CAPTURED, Payment::getCapturedAt,
			PaymentStatus.REFUNDED, Payment::getRefundedAt, PaymentStatus.CANCELLED, Payment::getCancelledAt,
			PaymentStatus.FAILED, Payment::getFailedAt);

	@Test
	void allowsOnlyTheDeclaredTransitions() {
		assertThat(actionsAllowedFrom(PaymentStatus.INITIATED)).containsExactlyInAnyOrder(PaymentAction.AUTHORIZE,
				PaymentAction.CANCEL, PaymentAction.FAIL);
		assertThat(actionsAllowedFrom(PaymentStatus.AUTHORIZED)).containsExactlyInAnyOrder(PaymentAction.CAPTURE,
				PaymentAction.CANCEL, PaymentAction.FAIL);
		assertThat(actionsAllowedFrom(PaymentStatus.CAPTURED)).containsExactly(PaymentAction.REFUND);

		assertThat(actionsAllowedFrom(PaymentStatus.REFUNDED)).isEmpty();
		assertThat(actionsAllowedFrom(PaymentStatus.CANCELLED)).isEmpty();
		assertThat(actionsAllowedFrom(PaymentStatus.FAILED)).isEmpty();
	}

	@Test
	void recognizesAReplayOnlyInTheTargetStatus() {
		for (PaymentAction action : PaymentAction.values()) {
			assertThat(action.isReplayOf(action.targetStatus())).as("replay of %s", action).isTrue();
		}

		assertThat(PaymentAction.AUTHORIZE.isReplayOf(PaymentStatus.INITIATED)).isFalse();
		assertThat(PaymentAction.REFUND.isReplayOf(PaymentStatus.CAPTURED)).isFalse();
	}

	@Test
	void refusesATransitionFromAStatusThatDoesNotAllowIt() {
		Payment payment = initiatedPayment();

		assertThatThrownBy(() -> payment.apply(PaymentAction.CAPTURE)).isInstanceOf(PaymentStateException.class)
			.hasMessageContaining("CAPTURE")
			.hasMessageContaining("INITIATED");
	}

	@Test
	void treatsARepeatOfTheReachedStatusAsAReplayInsteadOfAnError() {
		Payment payment = paymentIn(PaymentStatus.AUTHORIZED);

		assertThat(payment.apply(PaymentAction.AUTHORIZE)).isEqualTo(PaymentTransitionOutcome.REPLAYED);
		assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
	}

	@Test
	void everyActionStampsTheLifecycleTimestampOfItsTargetStatus() {
		for (PaymentAction action : PaymentAction.values()) {
			Payment payment = paymentIn(anyAllowedSource(action));

			payment.apply(action);

			assertThat(LIFECYCLE_TIMESTAMPS.get(action.targetStatus()).apply(payment))
				.as("timestamp stamped by %s", action)
				.isNotNull();
			assertThat(payment.getStatus()).isEqualTo(action.targetStatus());
		}
	}

	private static List<PaymentAction> actionsAllowedFrom(PaymentStatus status) {
		return Arrays.stream(PaymentAction.values()).filter((action) -> action.appliesFrom(status)).toList();
	}

	private static PaymentStatus anyAllowedSource(PaymentAction action) {
		return Arrays.stream(PaymentStatus.values()).filter(action::appliesFrom).findFirst().orElseThrow();
	}

	private static Payment paymentIn(PaymentStatus status) {
		Payment payment = initiatedPayment();
		if (status == PaymentStatus.INITIATED) {
			return payment;
		}
		payment.apply(PaymentAction.AUTHORIZE);
		if (status == PaymentStatus.AUTHORIZED) {
			return payment;
		}
		payment.apply(PaymentAction.CAPTURE);
		return payment;
	}

	private static Payment initiatedPayment() {
		return Payment.initiate(new BigDecimal("10.00"), "USD", "ORDER-0001", "Lifecycle test");
	}

}

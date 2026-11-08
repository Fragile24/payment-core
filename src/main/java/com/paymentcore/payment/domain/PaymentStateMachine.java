package com.paymentcore.payment.domain;

import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for payment lifecycle transitions.
 *
 * <p>Allowed source states are declared here rather than being spread across
 * entity methods, service switches, and controllers.</p>
 */
public final class PaymentStateMachine {

	private static final Map<PaymentAction, Set<PaymentStatus>> ALLOWED_SOURCES = Map.of(
			PaymentAction.AUTHORIZE, Set.of(PaymentStatus.INITIATED),
			PaymentAction.CAPTURE, Set.of(PaymentStatus.AUTHORIZED),
			PaymentAction.REFUND, Set.of(PaymentStatus.CAPTURED),
			PaymentAction.CANCEL, Set.of(PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED),
			PaymentAction.FAIL, Set.of(PaymentStatus.INITIATED, PaymentStatus.AUTHORIZED));

	private static final Set<PaymentStatus> TERMINAL_STATUSES = Set.of(PaymentStatus.REFUNDED,
			PaymentStatus.CANCELLED, PaymentStatus.FAILED);

	private PaymentStateMachine() {
	}

	public static boolean canApply(PaymentAction action, PaymentStatus currentStatus) {
		return ALLOWED_SOURCES.get(action).contains(currentStatus);
	}

	public static boolean isReplay(PaymentAction action, PaymentStatus currentStatus) {
		return currentStatus == action.targetStatus();
	}

	public static boolean isTerminal(PaymentStatus status) {
		return TERMINAL_STATUSES.contains(status);
	}

	public static Set<PaymentStatus> allowedSources(PaymentAction action) {
		return ALLOWED_SOURCES.get(action);
	}

}

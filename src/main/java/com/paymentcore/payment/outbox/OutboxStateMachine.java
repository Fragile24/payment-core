package com.paymentcore.payment.outbox;

import java.util.Set;

/**
 * Explicit outbox delivery state machine.
 *
 * <pre>
 * PENDING --success--> PUBLISHED
 * PENDING --retry-----> PENDING
 * PENDING --exhausted-> FAILED
 * FAILED  --retry-----> PENDING
 * </pre>
 */
public final class OutboxStateMachine {

	private static final Set<Transition> ALLOWED_TRANSITIONS = Set.of(
			new Transition(OutboxStatus.PENDING, OutboxStatus.PUBLISHED),
			new Transition(OutboxStatus.PENDING, OutboxStatus.PENDING),
			new Transition(OutboxStatus.PENDING, OutboxStatus.FAILED),
			new Transition(OutboxStatus.FAILED, OutboxStatus.PENDING));

	private OutboxStateMachine() {
	}

	public static boolean canTransition(OutboxStatus from, OutboxStatus to) {
		return ALLOWED_TRANSITIONS.contains(new Transition(from, to));
	}

	public static boolean isDelivered(OutboxStatus status) {
		return status == OutboxStatus.PUBLISHED;
	}

	public static OutboxStatus afterFailure(int attempts, int maxAttempts) {
		return attempts >= maxAttempts ? OutboxStatus.FAILED : OutboxStatus.PENDING;
	}

	private record Transition(OutboxStatus from, OutboxStatus to) {
	}

}

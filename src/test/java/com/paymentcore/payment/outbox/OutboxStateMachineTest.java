package com.paymentcore.payment.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxStateMachineTest {

	@Test
	void allowsOnlyDeclaredOutboxTransitions() {
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.PENDING, OutboxStatus.PUBLISHED)).isTrue();
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.PENDING, OutboxStatus.PENDING)).isTrue();
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.PENDING, OutboxStatus.FAILED)).isTrue();
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.FAILED, OutboxStatus.PENDING)).isTrue();

		assertThat(OutboxStateMachine.canTransition(OutboxStatus.PUBLISHED, OutboxStatus.PENDING)).isFalse();
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.PUBLISHED, OutboxStatus.FAILED)).isFalse();
		assertThat(OutboxStateMachine.canTransition(OutboxStatus.FAILED, OutboxStatus.PUBLISHED)).isFalse();
	}

	@Test
	void movesToFailedOnlyAfterAttemptsAreExhausted() {
		assertThat(OutboxStateMachine.afterFailure(1, 3)).isEqualTo(OutboxStatus.PENDING);
		assertThat(OutboxStateMachine.afterFailure(2, 3)).isEqualTo(OutboxStatus.PENDING);
		assertThat(OutboxStateMachine.afterFailure(3, 3)).isEqualTo(OutboxStatus.FAILED);
		assertThat(OutboxStateMachine.afterFailure(4, 3)).isEqualTo(OutboxStatus.FAILED);
	}

}

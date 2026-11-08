package com.paymentcore.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStateMachineTest {

	@Test
	void allowsOnlyDeclaredPaymentTransitions() {
		assertThat(PaymentStateMachine.canApply(PaymentAction.AUTHORIZE, PaymentStatus.INITIATED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.CAPTURE, PaymentStatus.AUTHORIZED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.REFUND, PaymentStatus.CAPTURED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.CANCEL, PaymentStatus.INITIATED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.CANCEL, PaymentStatus.AUTHORIZED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.FAIL, PaymentStatus.INITIATED)).isTrue();
		assertThat(PaymentStateMachine.canApply(PaymentAction.FAIL, PaymentStatus.AUTHORIZED)).isTrue();

		assertThat(PaymentStateMachine.canApply(PaymentAction.CAPTURE, PaymentStatus.INITIATED)).isFalse();
		assertThat(PaymentStateMachine.canApply(PaymentAction.REFUND, PaymentStatus.AUTHORIZED)).isFalse();
		assertThat(PaymentStateMachine.canApply(PaymentAction.CANCEL, PaymentStatus.CAPTURED)).isFalse();
		assertThat(PaymentStateMachine.canApply(PaymentAction.FAIL, PaymentStatus.CAPTURED)).isFalse();
		assertThat(PaymentStateMachine.canApply(PaymentAction.AUTHORIZE, PaymentStatus.REFUNDED)).isFalse();
	}

	@Test
	void recognizesReplayAndTerminalStates() {
		assertThat(PaymentStateMachine.isReplay(PaymentAction.AUTHORIZE, PaymentStatus.AUTHORIZED)).isTrue();
		assertThat(PaymentStateMachine.isReplay(PaymentAction.REFUND, PaymentStatus.REFUNDED)).isTrue();
		assertThat(PaymentStateMachine.isReplay(PaymentAction.AUTHORIZE, PaymentStatus.INITIATED)).isFalse();

		assertThat(PaymentStateMachine.isTerminal(PaymentStatus.REFUNDED)).isTrue();
		assertThat(PaymentStateMachine.isTerminal(PaymentStatus.CANCELLED)).isTrue();
		assertThat(PaymentStateMachine.isTerminal(PaymentStatus.FAILED)).isTrue();
		assertThat(PaymentStateMachine.isTerminal(PaymentStatus.CAPTURED)).isFalse();
	}

}

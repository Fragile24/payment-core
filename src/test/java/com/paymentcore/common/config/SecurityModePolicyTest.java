package com.paymentcore.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityModePolicyTest {

	@Test
	void refusesOpenModeInProduction() {
		assertThatThrownBy(() -> SecurityModePolicy.validate(SecurityMode.OPEN, true))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("prod profile");
	}

	@Test
	void allowsOpenModeOutsideProduction() {
		assertThatCode(() -> SecurityModePolicy.validate(SecurityMode.OPEN, false)).doesNotThrowAnyException();
	}

	@Test
	void allowsJwtModeInProduction() {
		assertThatCode(() -> SecurityModePolicy.validate(SecurityMode.JWT, true)).doesNotThrowAnyException();
	}

}

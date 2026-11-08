package com.paymentcore.reconciliation;

import com.paymentcore.ledger.ProjectionRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectionMismatchTest {

	private static final UUID PAYMENT_ID = UUID.randomUUID();

	@Test
	void describesAVersionOnlyDriftAsAVersionDifference() {
		ProjectionMismatch mismatch = new ProjectionMismatch(row("AUTHORIZED", "10.00", "USD", 3),
				row("AUTHORIZED", "10.00", "USD", 99));

		assertThat(mismatch.isMissing()).isFalse();
		assertThat(mismatch.type()).isEqualTo(ReconciliationFindingType.STALE_PROJECTION);
		assertThat(mismatch.details()).contains("version 99 (source 3)").doesNotContain("status");
	}

	@Test
	void describesEveryMirroredColumnThatDiffers() {
		ProjectionMismatch mismatch = new ProjectionMismatch(row("CAPTURED", "10.00", "USD", 1),
				row("INITIATED", "12.50", "EUR", 0));

		assertThat(mismatch.details()).contains("status INITIATED (source CAPTURED)")
			.contains("version 0 (source 1)")
			.contains("amount 12.50 (source 10.00)")
			.contains("currency EUR (source USD)");
	}

	@Test
	void reportsAMissingRowAsMissing() {
		ProjectionMismatch mismatch = new ProjectionMismatch(row("INITIATED", "10.00", "USD", 0), null);

		assertThat(mismatch.isMissing()).isTrue();
		assertThat(mismatch.type()).isEqualTo(ReconciliationFindingType.MISSING_PROJECTION);
		assertThat(mismatch.details()).isEqualTo("Projection row does not exist");
	}

	private static ProjectionRow row(String status, String amount, String currency, long version) {
		return new ProjectionRow(PAYMENT_ID, status, new BigDecimal(amount), currency, version);
	}

}

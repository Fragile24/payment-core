package com.paymentcore.reconciliation;

import com.paymentcore.ledger.ProjectionRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment whose projection differs from the source of truth.
 *
 * @param expected the values in {@code payments}
 * @param actual the values in {@code payment_projection}, {@code null} when the
 * row does not exist
 */
public record ProjectionMismatch(ProjectionRow expected, ProjectionRow actual) {

	public ProjectionMismatch {
		Objects.requireNonNull(expected, "expected");
	}

	public boolean isMissing() {
		return actual == null;
	}

	public UUID paymentId() {
		return expected.paymentId();
	}

	public String expectedStatus() {
		return expected.status();
	}

	public String actualStatus() {
		return actual == null ? null : actual.status();
	}

	public ReconciliationFindingType type() {
		return isMissing() ? ReconciliationFindingType.MISSING_PROJECTION : ReconciliationFindingType.STALE_PROJECTION;
	}

	/**
	 * Names the columns that actually differ, so a version-only drift is not
	 * reported as a status mismatch.
	 */
	public String details() {
		if (isMissing()) {
			return "Projection row does not exist";
		}
		List<String> differences = new ArrayList<>();
		if (!actual.status().equals(expected.status())) {
			differences.add("status " + actual.status() + " (source " + expected.status() + ")");
		}
		if (actual.version() != expected.version()) {
			differences.add("version " + actual.version() + " (source " + expected.version() + ")");
		}
		if (actual.amount().compareTo(expected.amount()) != 0) {
			differences.add("amount " + actual.amount().toPlainString() + " (source "
					+ expected.amount().toPlainString() + ")");
		}
		if (!actual.currency().equals(expected.currency())) {
			differences.add("currency " + actual.currency() + " (source " + expected.currency() + ")");
		}
		return "Projection differs from source: " + String.join(", ", differences);
	}

}

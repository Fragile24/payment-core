package com.paymentcore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL writes to the {@code payment_projection} read model.
 *
 * <p>The upsert is a single statement, not "select then insert or update". That
 * matters for two reasons: concurrent consumer instances cannot interleave
 * between the read and the write, and the {@code WHERE} clause implements the
 * ordering rule atomically — a delivery whose aggregate version is not newer
 * than the stored one is ignored, no matter how many times it arrives or in
 * which order.</p>
 */
@Repository
public class PaymentProjectionStore {

	private static final String UPSERT = """
			INSERT INTO payment_projection (payment_id, status, amount, currency, version, updated_at)
			VALUES (?, ?, ?, ?, ?, now())
			ON CONFLICT (payment_id) DO UPDATE SET
			    status = excluded.status,
			    amount = excluded.amount,
			    currency = excluded.currency,
			    version = excluded.version,
			    updated_at = now()
			""";

	/**
	 * The consumer path: a delivery never moves the projection backwards.
	 */
	private static final String APPLY = UPSERT + "WHERE payment_projection.version < excluded.version";

	/**
	 * The repair path: the source of truth wins, even if the projection was
	 * corrupted with a bogus version. The version is reset to the source version,
	 * so stale event deliveries are ignored again afterwards.
	 */
	private static final String OVERWRITE = UPSERT;

	private final JdbcTemplate jdbc;

	public PaymentProjectionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void apply(ProjectionRow row) {
		jdbc.update(APPLY, row.paymentId(), row.status(), row.amount(), row.currency(), row.version());
	}

	/**
	 * Rebuilds one projection row from the source of truth, ignoring the version
	 * guard.
	 */
	public void overwrite(ProjectionRow row) {
		jdbc.update(OVERWRITE, row.paymentId(), row.status(), row.amount(), row.currency(), row.version());
	}

}

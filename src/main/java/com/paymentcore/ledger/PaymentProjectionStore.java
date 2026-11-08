package com.paymentcore.ledger;

import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * SQL access to the {@code payment_projection} read model.
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
			INSERT INTO payment_projection
			    (payment_id, status, amount, currency, version, last_event_id, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, now())
			ON CONFLICT (payment_id) DO UPDATE SET
			    status = excluded.status,
			    amount = excluded.amount,
			    currency = excluded.currency,
			    version = excluded.version,
			    last_event_id = excluded.last_event_id,
			    updated_at = now()
			WHERE payment_projection.version < excluded.version
			""";

	private static final String OVERWRITE = """
			INSERT INTO payment_projection
			    (payment_id, status, amount, currency, version, last_event_id, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, now())
			ON CONFLICT (payment_id) DO UPDATE SET
			    status = excluded.status,
			    amount = excluded.amount,
			    currency = excluded.currency,
			    version = excluded.version,
			    last_event_id = excluded.last_event_id,
			    updated_at = now()
			""";

	private final JdbcTemplate jdbc;

	public PaymentProjectionStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Applies an event to the projection unless the stored version is already at
	 * least as new as the event.
	 * @return rows affected (0 when the event is stale or duplicate)
	 */
	public int apply(UUID paymentId, PaymentStatus status, BigDecimal amount, String currency, long version,
			UUID eventId) {
		return jdbc.update(UPSERT, paymentId, status.name(), amount, currency, version, eventId);
	}

	/**
	 * Repair path: the source of truth wins, even if the projection was corrupted
	 * with a bogus version. The version is reset to the source version, so stale
	 * event deliveries are ignored again afterwards.
	 */
	public void overwriteFromPayment(Payment payment) {
		jdbc.update(OVERWRITE, payment.getId(), payment.getStatus().name(), payment.getAmount(),
				payment.getCurrency(), payment.getVersion(), null);
	}

	/**
	 * Payments whose projection is missing or has a different status.
	 * @param limit bounded batch size so a large drift does not load the whole
	 * table into memory
	 */
	public List<ProjectionMismatch> findMismatches(int limit) {
		return jdbc.query("""
				SELECT p.id, p.status AS expected_status, pp.status AS actual_status
				FROM payments p
				LEFT JOIN payment_projection pp ON pp.payment_id = p.id
				WHERE pp.payment_id IS NULL OR pp.status <> p.status
				ORDER BY p.created_at
				LIMIT ?
				""", (rs, rowNum) -> new ProjectionMismatch(rs.getObject("id", UUID.class),
				rs.getString("expected_status"), rs.getString("actual_status")), limit);
	}

	/**
	 * Projection rows whose payment no longer exists. Reported, never deleted
	 * automatically: an orphan may mean a bug, a manual deletion, or a consumer
	 * that received an event for a rolled-back payment.
	 */
	public List<UUID> findOrphanPaymentIds(int limit) {
		return jdbc.queryForList("""
				SELECT pp.payment_id
				FROM payment_projection pp
				WHERE NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = pp.payment_id)
				ORDER BY pp.payment_id
				LIMIT ?
				""", UUID.class, limit);
	}

}

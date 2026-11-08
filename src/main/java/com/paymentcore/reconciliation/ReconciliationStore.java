package com.paymentcore.reconciliation;

import com.paymentcore.ledger.ProjectionRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The queries a reconciliation sweep runs.
 *
 * <p>They are plain SQL on purpose: the sweep compares two tables and reports
 * their backlog, so it owns the table view it needs instead of binding the
 * write-side JPA model. Reading the source of truth is the only write-side
 * knowledge reconciliation has.</p>
 */
@Repository
public class ReconciliationStore {

	private final JdbcTemplate jdbc;

	public ReconciliationStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long countPayments() {
		return jdbc.queryForObject("SELECT count(*) FROM payments", Long.class);
	}

	/**
	 * Payments whose projection row is missing or mirrors different values.
	 * @param limit bounded batch size so a large drift does not load the whole
	 * table into memory
	 */
	public List<ProjectionMismatch> findProjectionMismatches(int limit) {
		return jdbc.query("""
				SELECT p.id,
				       p.status AS expected_status,
				       p.amount AS expected_amount,
				       p.currency AS expected_currency,
				       p.version AS expected_version,
				       pp.status AS actual_status,
				       pp.amount AS actual_amount,
				       pp.currency AS actual_currency,
				       pp.version AS actual_version
				FROM payments p
				LEFT JOIN payment_projection pp ON pp.payment_id = p.id
				WHERE pp.payment_id IS NULL
				   OR pp.status <> p.status
				   OR pp.version <> p.version
				   OR pp.amount <> p.amount
				   OR pp.currency <> p.currency
				ORDER BY p.created_at
				LIMIT ?
				""", (rs, rowNum) -> {
			ProjectionRow expected = new ProjectionRow(rs.getObject("id", UUID.class),
					rs.getString("expected_status"), rs.getBigDecimal("expected_amount"),
					rs.getString("expected_currency"), rs.getLong("expected_version"));
			String actualStatus = rs.getString("actual_status");
			ProjectionRow actual = actualStatus == null ? null
					: new ProjectionRow(expected.paymentId(), actualStatus, rs.getBigDecimal("actual_amount"),
							rs.getString("actual_currency"), rs.getLong("actual_version"));
			return new ProjectionMismatch(expected, actual);
		}, limit);
	}

	/**
	 * Projection rows whose payment no longer exists. Reported, never deleted
	 * automatically: an orphan may mean a bug, a manual deletion, or a consumer
	 * that received an event for a rolled-back payment.
	 */
	public List<UUID> findOrphanProjectionIds(int limit) {
		return jdbc.queryForList("""
				SELECT pp.payment_id
				FROM payment_projection pp
				WHERE NOT EXISTS (SELECT 1 FROM payments p WHERE p.id = pp.payment_id)
				ORDER BY pp.payment_id
				LIMIT ?
				""", UUID.class, limit);
	}

	public long countPendingOutboxEvents() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'PENDING'", Long.class);
	}

	public long countPendingOutboxEventsCreatedBefore(Instant createdBefore) {
		return jdbc.queryForObject(
				"SELECT count(*) FROM outbox_events WHERE status = 'PENDING' AND created_at < ?", Long.class,
				Timestamp.from(createdBefore));
	}

	public long countFailedOutboxEvents() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'", Long.class);
	}

}

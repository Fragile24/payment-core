package com.paymentcore.reconciliation;

import com.paymentcore.ledger.PaymentProjectionStore;
import com.paymentcore.ledger.ProjectionRow;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rebuilds projection rows from the source of truth.
 *
 * <p>Repair reads {@code payments} instead of republishing events. The source
 * row is the authority for the current state, and re-projecting it is
 * idempotent: running the repair twice produces the same row, and a stale event
 * that arrives later is rejected by the version guard in
 * {@link PaymentProjectionStore#apply}.</p>
 */
@Service
public class ProjectionRepairer {

	private static final String PAYMENT_ROWS = """
			SELECT id, status, amount, currency, version FROM payments WHERE id IN (:paymentIds)
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final PaymentProjectionStore projection;

	public ProjectionRepairer(NamedParameterJdbcTemplate jdbc, PaymentProjectionStore projection) {
		this.jdbc = jdbc;
		this.projection = projection;
	}

	@Transactional
	public long repair(List<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return 0;
		}
		List<ProjectionRow> rows = jdbc.query(PAYMENT_ROWS, Map.of("paymentIds", paymentIds),
				(rs, rowNum) -> new ProjectionRow(rs.getObject("id", UUID.class), rs.getString("status"),
						rs.getBigDecimal("amount"), rs.getString("currency"), rs.getLong("version")));
		rows.forEach(projection::overwrite);
		return rows.size();
	}

}

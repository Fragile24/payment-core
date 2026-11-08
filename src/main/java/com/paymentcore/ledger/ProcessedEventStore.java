package com.paymentcore.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Delivery-level idempotency.
 *
 * <p>A message can be delivered more than once (consumer restart, broker
 * redelivery, retry after a timeout). Recording the delivery id in the same
 * transaction as the projection change makes "apply once" atomic:
 * {@code ON CONFLICT DO NOTHING} returns 0 rows for a duplicate, and the
 * consumer skips the rest.</p>
 *
 * <p>The key includes the consumer name so independent consumer groups can each
 * process the same event.</p>
 */
@Repository
public class ProcessedEventStore {

	private final JdbcTemplate jdbc;

	public ProcessedEventStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @return 1 when this delivery is new, 0 when it was already processed
	 */
	public int insertIfAbsent(UUID eventId, String consumer) {
		return jdbc.update("""
				INSERT INTO processed_events (event_id, consumer, processed_at)
				VALUES (?, ?, now())
				ON CONFLICT DO NOTHING
				""", eventId, consumer);
	}

}

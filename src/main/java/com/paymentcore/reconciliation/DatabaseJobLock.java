package com.paymentcore.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * A lease stored in {@code scheduled_job_lock}, the same idea as ShedLock
 * without an extra dependency.
 *
 * <p>{@code UPDATE ... WHERE locked_until < now()} is atomic in PostgreSQL, so
 * when several instances fire the same cron, exactly one gets the lease. If
 * that instance crashes, the lease expires after {@code app.reconciliation.lock-ttl}
 * and the next tick takes over; no manual cleanup is required.</p>
 */
@Repository
public class DatabaseJobLock {

	private final JdbcTemplate jdbc;

	public DatabaseJobLock(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean tryAcquire(String jobName, String owner, Instant lockedUntil) {
		// Make the job self-registering; the migration seeds the first row but a
		// new job name should not require a schema change.
		jdbc.update("""
				INSERT INTO scheduled_job_lock (job_name, locked_until, locked_by)
				VALUES (?, now() - INTERVAL '1 day', 'bootstrap')
				ON CONFLICT DO NOTHING
				""", jobName);
		return jdbc.update("""
				UPDATE scheduled_job_lock
				SET locked_until = ?, locked_by = ?
				WHERE job_name = ? AND locked_until < now()
				""", Timestamp.from(lockedUntil), owner, jobName) == 1;
	}

	public void release(String jobName, String owner) {
		jdbc.update("""
				UPDATE scheduled_job_lock
				SET locked_until = now()
				WHERE job_name = ? AND locked_by = ?
				""", jobName, owner);
	}

}

package com.paymentcore.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Reconciliation metrics.
 *
 * <p>{@code reconciliation.findings{type=...}} is the alerting signal: a
 * non-zero {@code ORPHAN_PROJECTION} or {@code OUTBOX_FAILED} rate means
 * something is wrong that the job deliberately did not fix.</p>
 */
@Component
public class ReconciliationMetrics {

	static final String RUNS = "reconciliation.runs";

	static final String DURATION = "reconciliation.duration";

	static final String FINDINGS = "reconciliation.findings";

	static final String REPAIRED = "reconciliation.repaired";

	private final MeterRegistry meterRegistry;

	public ReconciliationMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	public void recordCompleted(ReconciliationSummary summary, Duration duration) {
		meterRegistry.counter(RUNS, "result", "completed").increment();
		meterRegistry.timer(DURATION, "result", "completed").record(duration);
		meterRegistry.counter(REPAIRED).increment(summary.repaired());
		summary.findings()
			.forEach((finding) -> meterRegistry.counter(FINDINGS, "type", finding.type().name()).increment());
	}

	public void recordSkipped() {
		meterRegistry.counter(RUNS, "result", "skipped").increment();
	}

	public void recordFailed(Duration duration) {
		meterRegistry.counter(RUNS, "result", "failed").increment();
		meterRegistry.timer(DURATION, "result", "failed").record(duration);
	}

}

package com.paymentcore.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a fixed delay.
 *
 * <p>The scheduler is a thin adapter: all the logic sits in
 * {@link ReconciliationService}, which is what tests call directly. Exceptions
 * are caught here so one failed sweep does not stop the schedule; the failure is
 * persisted as a {@code FAILED} run by the service before it is rethrown.</p>
 */
@Component
@ConditionalOnProperty(name = "app.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

	private final ReconciliationService reconciliationService;

	public ReconciliationScheduler(ReconciliationService reconciliationService) {
		this.reconciliationService = reconciliationService;
	}

	@Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:300000}",
			initialDelayString = "${app.reconciliation.initial-delay-ms:30000}")
	public void reconcileOnSchedule() {
		try {
			reconciliationService.reconcile();
		}
		catch (RuntimeException ex) {
			log.error("Scheduled reconciliation failed; the next tick will try again", ex);
		}
	}

}

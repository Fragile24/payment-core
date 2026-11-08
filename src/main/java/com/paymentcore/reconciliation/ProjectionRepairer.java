package com.paymentcore.reconciliation;

import com.paymentcore.ledger.PaymentProjectionStore;
import com.paymentcore.payment.domain.Payment;
import com.paymentcore.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

	private final PaymentRepository payments;

	private final PaymentProjectionStore projection;

	public ProjectionRepairer(PaymentRepository payments, PaymentProjectionStore projection) {
		this.payments = payments;
		this.projection = projection;
	}

	@Transactional
	public long repair(List<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return 0;
		}
		List<Payment> found = payments.findAllById(paymentIds);
		found.forEach(projection::overwriteFromPayment);
		return found.size();
	}

}

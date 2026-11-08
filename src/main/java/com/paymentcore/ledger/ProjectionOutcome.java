package com.paymentcore.ledger;

/**
 * Outcome of one delivered event.
 */
public enum ProjectionOutcome {

	/** The event changed (or safely confirmed) the projection. */
	PROCESSED,

	/** The delivery id was already processed; the event was ignored. */
	DUPLICATE

}

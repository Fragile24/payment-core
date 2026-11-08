package com.paymentcore.payment.event;

import java.util.UUID;

public interface PaymentEvent {

	UUID eventId();

	UUID paymentId();

}

package com.paymentcore.payment.event;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable RabbitMQ topology for payment events.
 *
 * <p>Every consumer group owns a queue. The publisher only knows the exchange
 * and routing keys; adding or removing a consumer never changes publisher code.
 * {@code payment.projection} is the queue of the in-house projection consumer
 * and receives both event types. The other two queues remain available for
 * other consumers (ledger, notifications, analytics).</p>
 *
 * <p>The main queues are bound to a dead-letter exchange so a consumer that
 * rejects a message (poison payload, repeated processing failure) does not
 * silently lose it. The naming convention is fixed here:
 * {@code <queue>.dlq}, dead-lettered with the original queue name as routing
 * key, which makes the {@code rabbitmqctl list_queues} output easy to read.</p>
 *
 * <p>The publisher side already retries through the outbox; the DLQ covers the
 * consumer side, which the outbox cannot see.</p>
 */
@Configuration
public class PaymentMessagingConfiguration {

	@Bean
	TopicExchange paymentEventsExchange(MessagingProperties properties) {
		return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
	}

	@Bean
	TopicExchange paymentDeadLetterExchange(MessagingProperties properties) {
		return ExchangeBuilder.topicExchange(properties.deadLetterExchange()).durable(true).build();
	}

	@Bean
	Declarables paymentTopology(MessagingProperties properties, TopicExchange paymentEventsExchange,
			TopicExchange paymentDeadLetterExchange) {
		Queue createdQueue = deadLetteringQueue(properties.createdQueue(), properties.deadLetterExchange());
		Queue statusChangedQueue = deadLetteringQueue(properties.statusChangedQueue(),
				properties.deadLetterExchange());
		Queue projectionQueue = deadLetteringQueue(properties.projectionQueue(), properties.deadLetterExchange());
		Queue createdDeadLetterQueue = deadLetterQueue(properties.createdQueue());
		Queue statusChangedDeadLetterQueue = deadLetterQueue(properties.statusChangedQueue());
		Queue projectionDeadLetterQueue = deadLetterQueue(properties.projectionQueue());

		return new Declarables(createdQueue, statusChangedQueue, projectionQueue, createdDeadLetterQueue,
				statusChangedDeadLetterQueue, projectionDeadLetterQueue,
				BindingBuilder.bind(createdQueue).to(paymentEventsExchange).with(properties.createdRoutingKey()),
				BindingBuilder.bind(statusChangedQueue)
					.to(paymentEventsExchange)
					.with(properties.statusChangedRoutingKey()),
				BindingBuilder.bind(projectionQueue)
					.to(paymentEventsExchange)
					.with(properties.createdRoutingKey()),
				BindingBuilder.bind(projectionQueue)
					.to(paymentEventsExchange)
					.with(properties.statusChangedRoutingKey()),
				BindingBuilder.bind(createdDeadLetterQueue)
					.to(paymentDeadLetterExchange)
					.with(properties.createdQueue()),
				BindingBuilder.bind(statusChangedDeadLetterQueue)
					.to(paymentDeadLetterExchange)
					.with(properties.statusChangedQueue()),
				BindingBuilder.bind(projectionDeadLetterQueue)
					.to(paymentDeadLetterExchange)
					.with(properties.projectionQueue()));
	}

	private static Queue deadLetteringQueue(String name, String deadLetterExchange) {
		return QueueBuilder.durable(name)
			.deadLetterExchange(deadLetterExchange)
			.deadLetterRoutingKey(name)
			.build();
	}

	private static Queue deadLetterQueue(String sourceQueue) {
		return QueueBuilder.durable(sourceQueue + ".dlq").build();
	}

}

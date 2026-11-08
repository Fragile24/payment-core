package com.paymentcore.payment.event;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Durable RabbitMQ topology for payment events.
 *
 * <p>This iteration publishes lifecycle events directly after commit. The next
 * reliability step is the transactional outbox pattern, which removes the
 * remaining chance of losing an event if the broker is unavailable right after
 * the database commit.</p>
 */
@Configuration
public class PaymentMessagingConfiguration {

	@Bean
	TopicExchange paymentEventsExchange(MessagingProperties properties) {
		return ExchangeBuilder.topicExchange(properties.exchange()).durable(true).build();
	}

	@Bean
	Declarables paymentTopology(MessagingProperties properties, TopicExchange paymentEventsExchange) {
		Queue createdQueue = QueueBuilder.durable(properties.createdQueue()).build();
		Queue statusChangedQueue = QueueBuilder.durable(properties.statusChangedQueue()).build();
		return new Declarables(createdQueue, statusChangedQueue,
				BindingBuilder.bind(createdQueue).to(paymentEventsExchange).with(properties.createdRoutingKey()),
				BindingBuilder.bind(statusChangedQueue)
					.to(paymentEventsExchange)
					.with(properties.statusChangedRoutingKey()));
	}

	@Bean
	MessageConverter jsonMessageConverter() {
		return new JacksonJsonMessageConverter();
	}

}

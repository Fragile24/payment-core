package com.paymentcore.ledger;

import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Listener container with an explicit retry contract.
 *
 * <p>Three attempts (one initial plus two retries) with a short exponential
 * backoff cover transient database or deserialization hiccups. When the budget
 * is exhausted, {@link RejectAndDontRequeueRecoverer} rejects the message
 * without requeue and the queue's dead-letter exchange takes over. Requeueing a
 * poison message forever is the classic way to turn one bad event into a stuck
 * consumer.</p>
 */
@Configuration
public class LedgerConsumerConfiguration {

	@Bean
	SimpleRabbitListenerContainerFactory ledgerListenerContainerFactory(ConnectionFactory connectionFactory,
			MessageConverter messageConverter) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setMessageConverter(messageConverter);
		factory.setDefaultRequeueRejected(false);
		factory.setConcurrentConsumers(1);
		factory.setMaxConcurrentConsumers(4);
		factory.setPrefetchCount(20);
		factory.setAdviceChain(RetryInterceptorBuilder.stateless()
			.maxRetries(2)
			.backOffOptions(500, 2.0, 5000)
			.recoverer(new RejectAndDontRequeueRecoverer())
			.build());
		return factory;
	}

}

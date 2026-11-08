package com.paymentcore.payment.event;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentMessagingConfigurationTest {

	private final PaymentMessagingConfiguration configuration = new PaymentMessagingConfiguration();

	private final MessagingProperties properties = new MessagingProperties("payment.events", "payment.events.dlx",
			"payment.created", "payment.created", "payment.status-changed", "payment.status-changed",
			"payment.projection");

	@Test
	void mainQueuesDeadLetterToTheDeadLetterExchange() {
		Declarables declarables = topology();

		assertThat(queue(declarables, "payment.created").getArguments())
			.containsEntry("x-dead-letter-exchange", "payment.events.dlx")
			.containsEntry("x-dead-letter-routing-key", "payment.created");
		assertThat(queue(declarables, "payment.status-changed").getArguments())
			.containsEntry("x-dead-letter-exchange", "payment.events.dlx")
			.containsEntry("x-dead-letter-routing-key", "payment.status-changed");
		assertThat(queue(declarables, "payment.projection").getArguments())
			.containsEntry("x-dead-letter-exchange", "payment.events.dlx")
			.containsEntry("x-dead-letter-routing-key", "payment.projection");
	}

	@Test
	void projectionQueueReceivesBothEventTypes() {
		Declarables declarables = topology();

		List<Binding> bindings = declarables.getDeclarablesByType(Binding.class)
			.stream()
			.filter((binding) -> "payment.projection".equals(binding.getDestination()))
			.toList();

		assertThat(bindings).hasSize(2);
		assertThat(bindings.stream().map(Binding::getRoutingKey))
			.containsExactlyInAnyOrder("payment.created", "payment.status-changed");
		assertThat(bindings).allSatisfy((binding) -> assertThat(binding.getExchange()).isEqualTo("payment.events"));
	}

	@Test
	void deadLetterQueuesAreBoundToTheDeadLetterExchange() {
		Declarables declarables = topology();

		assertThat(queue(declarables, "payment.created.dlq")).isNotNull();
		assertThat(queue(declarables, "payment.status-changed.dlq")).isNotNull();
		assertThat(queue(declarables, "payment.projection.dlq")).isNotNull();

		List<Binding> deadLetterBindings = declarables.getDeclarablesByType(Binding.class)
			.stream()
			.filter((binding) -> binding.getExchange().equals("payment.events.dlx"))
			.toList();

		assertThat(deadLetterBindings).hasSize(3);
		assertThat(deadLetterBindings.stream()
			.map(Binding::getRoutingKey)
			.collect(Collectors.toSet())).containsExactlyInAnyOrder("payment.created", "payment.status-changed",
					"payment.projection");
	}

	@Test
	void deadLetterExchangeIsADurableTopicExchange() {
		TopicExchange exchange = configuration.paymentDeadLetterExchange(properties);

		assertThat(exchange.getName()).isEqualTo("payment.events.dlx");
		assertThat(exchange.isDurable()).isTrue();
	}

	private Declarables topology() {
		TopicExchange events = configuration.paymentEventsExchange(properties);
		TopicExchange deadLetters = configuration.paymentDeadLetterExchange(properties);
		return configuration.paymentTopology(properties, events, deadLetters);
	}

	private Queue queue(Declarables declarables, String name) {
		Map<String, Queue> queuesByName = declarables.getDeclarablesByType(Queue.class)
			.stream()
			.collect(Collectors.toMap(Queue::getName, Function.identity()));
		return queuesByName.get(name);
	}

}

package com.paymentcore;

import org.springframework.boot.SpringApplication;

public class TestPaymentCoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

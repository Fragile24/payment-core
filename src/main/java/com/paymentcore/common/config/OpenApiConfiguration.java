package com.paymentcore.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

	@Bean
	OpenAPI paymentCoreOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Payment Core API").version("v1")
				.description("Sandbox payment API with idempotent payment creation and an audit trail."))
			.components(new Components().addSecuritySchemes("bearerAuth",
					new SecurityScheme().type(SecurityScheme.Type.HTTP)
						.scheme("bearer")
						.bearerFormat("JWT")
						.description("JWT access token. Required when app.security.mode=JWT.")))
			.addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
	}

}

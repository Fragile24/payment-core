package com.paymentcore.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

	/**
	 * Endpoints that are reachable without a token in every mode: probes, metrics,
	 * the OpenAPI document and error rendering.
	 */
	private static final String[] PUBLIC_ENDPOINTS = { "/actuator/health", "/actuator/health/**", "/actuator/info",
			"/actuator/prometheus", "/error", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**" };

	private final SecurityProperties properties;

	public SecurityConfiguration(SecurityProperties properties, Environment environment) {
		SecurityModePolicy.validate(properties.mode(), environment.acceptsProfiles(Profiles.of("prod")));
		this.properties = properties;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

		switch (properties.mode()) {
			case JWT -> http.oauth2ResourceServer((oauth2) -> oauth2.jwt(Customizer.withDefaults()))
				.authorizeHttpRequests((requests) -> requests.requestMatchers(PUBLIC_ENDPOINTS)
					.permitAll()
					.anyRequest()
					.authenticated());
			case OPEN -> {
				log.warn("Security mode is OPEN: payment endpoints are accessible without authentication.");
				http.authorizeHttpRequests((requests) -> requests.requestMatchers(PUBLIC_ENDPOINTS)
					.permitAll()
					.anyRequest()
					.permitAll());
			}
		}

		return http.build();
	}

}

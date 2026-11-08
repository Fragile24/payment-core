package com.paymentcore.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

	private final SecurityProperties properties;

	public SecurityConfiguration(SecurityProperties properties) {
		this.properties = properties;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(requests -> {
				requests.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/error",
						"/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
					.permitAll();
				switch (properties.mode()) {
					case JWT -> requests.anyRequest().authenticated();
					case OPEN -> {
						log.warn("Security mode is OPEN: payment endpoints are accessible without authentication.");
						requests.anyRequest().permitAll();
					}
				}
			});

		if (properties.mode() == SecurityMode.JWT) {
			http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
		}

		return http.build();
	}

}

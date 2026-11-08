package com.paymentcore.common.observability;

import com.paymentcore.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the correlation-id contract end to end: the header is echoed, the
 * id is attached to error payloads, and a client cannot smuggle log lines into
 * the log stream through the header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RequestIdIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void echoesTheProvidedRequestId() throws Exception {
		mockMvc.perform(get("/api/v1/payments").header(RequestIdFilter.HEADER_NAME, "checkout-request-0001"))
			.andExpect(status().isOk())
			.andExpect(header().string(RequestIdFilter.HEADER_NAME, "checkout-request-0001"));
	}

	@Test
	void includesTheRequestIdInValidationProblems() throws Exception {
		mockMvc
			.perform(post("/api/v1/payments").header(RequestIdFilter.HEADER_NAME, "checkout-request-0002")
				.header("Idempotency-Key", "request-id-0002")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"amount": 0, "currency": "USD"}
						"""))
			.andExpect(status().isBadRequest())
			.andExpect(header().string(RequestIdFilter.HEADER_NAME, "checkout-request-0002"))
			.andExpect(jsonPath("$.requestId").value("checkout-request-0002"))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void includesTheRequestIdInNotFoundProblems() throws Exception {
		mockMvc
			.perform(get("/api/v1/payments/{paymentId}", "00000000-0000-0000-0000-000000000000")
				.header(RequestIdFilter.HEADER_NAME, "checkout-request-0003"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.requestId").value("checkout-request-0003"));
	}

	@Test
	void generatesAndEchoesAnIdWhenTheClientSendsNone() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/payments"))
			.andExpect(status().isOk())
			.andExpect(header().string(RequestIdFilter.HEADER_NAME,
					matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
			.andReturn();

		assertThat(result.getResponse().getHeader(RequestIdFilter.HEADER_NAME)).isNotBlank();
	}

}

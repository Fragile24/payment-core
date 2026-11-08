package com.paymentcore.common.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@AfterEach
	void clearMdc() {
		MDC.clear();
	}

	@Test
	void acceptsAGatewayProvidedId() {
		assertThat(RequestIdFilter.resolveRequestId("gateway-7f3c2f1e-4d4a")).isEqualTo("gateway-7f3c2f1e-4d4a");
	}

	@Test
	void generatesAnIdWhenTheHeaderIsMissing() {
		assertThat(RequestIdFilter.resolveRequestId(null)).isNotBlank().hasSize(36);
	}

	@Test
	void rejectsIdsThatCouldInjectLogLines() {
		String resolved = RequestIdFilter.resolveRequestId("abc\nINFO forged log line");

		assertThat(resolved).doesNotContain("\n").doesNotContain("INFO forged");
	}

	@Test
	void echoesTheIdAndClearsTheMdcAfterTheRequest() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payments");
		request.addHeader(RequestIdFilter.HEADER_NAME, "checkout-request-0001");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> idSeenByApplicationCode = new AtomicReference<>();
		FilterChain chain = (filterRequest, filterResponse) -> idSeenByApplicationCode
			.set(MDC.get(RequestIdFilter.MDC_KEY));

		filter.doFilter(request, response, chain);

		assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("checkout-request-0001");
		assertThat(idSeenByApplicationCode.get()).isEqualTo("checkout-request-0001");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void clearsTheMdcEvenWhenTheChainFails() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/payments");
		request.addHeader(RequestIdFilter.HEADER_NAME, "checkout-request-0002");
		FilterChain chain = (filterRequest, filterResponse) -> {
			throw new IllegalStateException("downstream failure");
		};

		try {
			filter.doFilter(request, new MockHttpServletResponse(), chain);
		}
		catch (IllegalStateException ex) {
			// Expected: the filter must not swallow application failures.
		}

		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

}

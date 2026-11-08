package com.paymentcore.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns a correlation id to every request, exposes it to logs through the MDC
 * and echoes it back in the {@code X-Request-Id} response header.
 *
 * <p>Incoming ids are accepted only when they are short and use a safe alphabet,
 * so a client cannot inject arbitrary text into log lines. Missing or invalid
 * ids are replaced by a generated UUID. Because the value lives in the MDC,
 * every log line written while the request is handled carries it, including
 * logs from Spring, Hibernate and the outbox scheduler triggered by the
 * request.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Request-Id";

	public static final String MDC_KEY = "requestId";

	/**
	 * Accepts ids produced by common gateways and tracing libraries while keeping
	 * the value log-safe.
	 */
	private static final Pattern ACCEPTED_ID = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
		MDC.put(MDC_KEY, requestId);
		response.setHeader(HEADER_NAME, requestId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			// Servlet threads are pooled: a leaked id would be attached to the next
			// unrelated request.
			MDC.remove(MDC_KEY);
		}
	}

	/**
	 * Returns the incoming id when it is acceptable, otherwise a new UUID.
	 * @param incomingId the value of the {@value #HEADER_NAME} header, may be null
	 * @return a log-safe correlation id
	 */
	public static String resolveRequestId(String incomingId) {
		if (incomingId != null && ACCEPTED_ID.matcher(incomingId).matches()) {
			return incomingId;
		}
		return UUID.randomUUID().toString();
	}

}

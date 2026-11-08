package com.paymentcore.common.config;

/**
 * Startup policy for the security mode.
 *
 * <p>Failing fast is a production requirement: a service that starts with
 * authentication disabled in a production profile is a security incident, not a
 * configuration warning. Keeping the rule in a small pure class makes it
 * directly testable, unlike a rule hidden inside a Spring configuration.</p>
 */
public final class SecurityModePolicy {

	private SecurityModePolicy() {
	}

	/**
	 * Rejects the open mode when the {@code prod} profile is active.
	 * @param mode the configured security mode
	 * @param productionProfile whether the {@code prod} profile is active
	 * @throws IllegalStateException when open mode is requested in production
	 */
	public static void validate(SecurityMode mode, boolean productionProfile) {
		if (productionProfile && mode == SecurityMode.OPEN) {
			throw new IllegalStateException(
					"app.security.mode=OPEN is not allowed with the prod profile. Configure JWT/OpenID Connect.");
		}
	}

}

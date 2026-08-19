package com.noveltea.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * How a reset link reaches its owner.
 *
 * <p>Separated from the service that mints tokens so email can arrive later without
 * touching any of this. Until it does, a self-hosted operator can complete a reset by
 * reading the log — which is honest for a single-operator install, and unacceptable for
 * anything multi-tenant. That distinction is why the default implementation says so out
 * loud on every use.
 */
public interface PasswordResetDelivery {

    void deliver(String email, String token);

    /**
     * The fallback when no mail sender is configured. A real implementation replaces it by
     * being declared {@code @Primary}.
     */
    @Component
    class LoggingDelivery implements PasswordResetDelivery {

        private static final Logger log = LoggerFactory.getLogger(LoggingDelivery.class);

        @Override
        public void deliver(String email, String token) {
            log.warn("No mail sender is configured, so this reset link is only being logged. "
                    + "Anyone who can read these logs can take over the account. "
                    + "Reset for {}: token={}", email, token);
        }
    }
}

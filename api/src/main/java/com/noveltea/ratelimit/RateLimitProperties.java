package com.noveltea.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Limits for the unauthenticated endpoints only.
 *
 * <p>Nothing here touches sync. A client catching up makes hundreds of requests in
 * seconds — a first sync fetches every document, a resync rebuilds from scratch — so any
 * rate low enough to deter abuse would break exactly the flows where failure is most
 * visible. The credential endpoints have no such shape and are where the attack actually
 * is.
 */
@ConfigurationProperties(prefix = "noveltea.rate-limit")
public record RateLimitProperties(
        Boolean enabled,
        Integer loginAttemptsPerWindow,
        Integer registrationsPerWindow,
        Integer pairingAttemptsPerWindow,
        Duration window) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        // Generous for a person mistyping a password, useless for guessing one.
        loginAttemptsPerWindow = orDefault(loginAttemptsPerWindow, 10);
        registrationsPerWindow = orDefault(registrationsPerWindow, 5);
        pairingAttemptsPerWindow = orDefault(pairingAttemptsPerWindow, 10);
        window = window == null ? Duration.ofMinutes(5) : window;
    }

    private static Integer orDefault(Integer value, int fallback) {
        return value == null || value < 1 ? fallback : value;
    }
}

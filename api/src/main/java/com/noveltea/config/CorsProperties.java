package com.noveltea.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins exact origins the browser client is served from. Empty means no
 *     cross-origin requests are permitted, which is correct when the API and the app share
 *     an origin. A separate front-end deployment must list its origin here — wildcards are
 *     not accepted, because credentials are sent on every request.
 */
@ConfigurationProperties(prefix = "noveltea.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}

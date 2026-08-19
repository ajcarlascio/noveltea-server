package com.noveltea.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtSecret base64 HMAC key, at least 32 bytes decoded. Deliberately has no
 *     default: a fallback secret is a fallback that reaches production.
 */
@ConfigurationProperties(prefix = "noveltea.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration pairingCodeTtl) {

    public AuthProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(60) : refreshTokenTtl;
        pairingCodeTtl = pairingCodeTtl == null ? Duration.ofMinutes(10) : pairingCodeTtl;
    }
}

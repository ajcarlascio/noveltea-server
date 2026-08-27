package com.noveltea.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param jwtSecret base64 HMAC key, at least 32 bytes decoded. Deliberately has no
 *     default: a fallback secret is a fallback that reaches production.
 * @param openRegistration whether anyone who can reach the instance may make an account.
 *     False unless asked for, which is the opposite of a hosted service's default and the
 *     right one for somebody's own server: accounts come from an administrator. A missing
 *     property binds a primitive boolean to false, so the safe answer is also the one you
 *     get by not configuring anything.
 */
@ConfigurationProperties(prefix = "noveltea.auth")
public record AuthProperties(
        String jwtSecret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration pairingCodeTtl,
        boolean openRegistration) {

    public AuthProperties {
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
        refreshTokenTtl = refreshTokenTtl == null ? Duration.ofDays(60) : refreshTokenTtl;
        pairingCodeTtl = pairingCodeTtl == null ? Duration.ofMinutes(10) : pairingCodeTtl;
    }
}

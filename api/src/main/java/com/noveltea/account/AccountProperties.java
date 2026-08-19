package com.noveltea.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param deletionGrace how long an account stays recoverable after deletion is requested.
 *     This is the one action a person takes in a bad moment and cannot undo, so the
 *     default is generous.
 * @param passwordResetTtl how long a reset link works. Short, because it is a credential
 *     sitting in an inbox.
 */
@ConfigurationProperties(prefix = "noveltea.account")
public record AccountProperties(Duration deletionGrace, Duration passwordResetTtl) {

    public AccountProperties {
        deletionGrace = deletionGrace == null ? Duration.ofDays(14) : deletionGrace;
        passwordResetTtl = passwordResetTtl == null ? Duration.ofHours(1) : passwordResetTtl;
    }
}

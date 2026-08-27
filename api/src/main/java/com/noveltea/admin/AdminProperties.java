package com.noveltea.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The account an instance starts life with.
 *
 * <p>Unlike {@code noveltea.auth.jwt-secret}, this one <em>does</em> have defaults, and
 * for the opposite reason. A signing key with a default is a key that silently reaches
 * production and stays; a first password with a default is a credential the server
 * immediately refuses to let anyone keep — {@code must_change_password} is set for any
 * password that does not clear {@link com.noveltea.auth.Passwords#MINIMUM_LENGTH}, which
 * the built-in {@code admin} deliberately does not. So the worst case is an operator who
 * has to change it, not an operator who never notices they should have.
 *
 * @param enabled false skips the check entirely. For test contexts and for an operator who
 *     provisions accounts by some other means; an instance with no admin cannot create
 *     users through the API.
 * @param email the address to sign in as. Existing and non-guest means "promote this one"
 *     rather than "create it".
 * @param password used only when the account is created. Never applied to an account that
 *     already exists — a restart must not reset a password somebody has since chosen.
 */
@ConfigurationProperties(prefix = "noveltea.admin")
public record AdminProperties(Boolean enabled, String email, String password) {

    public static final String DEFAULT_EMAIL = "admin@localhost";

    /**
     * Short on purpose, and shorter than {@code Passwords.MINIMUM_LENGTH} on purpose:
     * that is precisely what marks the account as needing a real password before it can
     * do anything.
     */
    public static final String DEFAULT_PASSWORD = "admin";

    public AdminProperties {
        enabled = enabled == null || enabled;
        email = email == null || email.isBlank() ? DEFAULT_EMAIL : email.trim();
        password = password == null || password.isBlank() ? DEFAULT_PASSWORD : password;
    }
}

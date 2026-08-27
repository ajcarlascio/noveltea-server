package com.noveltea.admin;

import com.noveltea.auth.Passwords;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gives a fresh instance somebody to sign in as.
 *
 * <p>Runs once per startup, after Liquibase. The rule it enforces is narrow on purpose:
 * <strong>it acts only when the instance has no administrator at all.</strong> A server
 * that already has one is left completely alone — no password is rewritten, no flag is
 * re-set — because otherwise every restart would undo whatever the operator had since
 * configured, and a container that restarts on a crash loop would do it repeatedly.
 *
 * <p>Two admin-less shapes exist and they are not the same thing:
 *
 * <ul>
 *   <li><b>Empty instance.</b> Create the configured account. This is first run.
 *   <li><b>Existing instance, upgraded into this feature.</b> Accounts already exist and
 *       none of them is an admin, because the column did not exist when they were made.
 *       Promoting the account whose address the operator configured is the only safe
 *       answer: picking one — the oldest, the first — would hand the server to whoever
 *       happened to sign up first, on a machine where that may well be a guest.
 * </ul>
 *
 * <p>Deliberately not idempotent by way of "always write the configured state": it is
 * idempotent by way of doing nothing once the instance can administer itself.
 */
@Component
@ConditionalOnProperty(value = "noveltea.admin.enabled", havingValue = "true", matchIfMissing = true)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final AdminProperties properties;

    public AdminBootstrap(JdbcClient jdbc, PasswordEncoder passwords, AdminProperties properties) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.properties = properties;
    }

    public enum Result {
        /** The instance had no accounts matching the configured address; one was made. */
        CREATED,
        /** The address already had an ordinary account; it is now also an administrator. */
        PROMOTED,
        /** An administrator already existed. Nothing was touched. */
        ALREADY_PRESENT,
        /** The configured address belongs to a guest, which cannot hold a password. */
        REFUSED_GUEST
    }

    /**
     * @param mustChangePassword whether the account still has to choose a real password
     *     before the API will do anything else for it
     */
    public record Outcome(Result result, String email, boolean mustChangePassword) {}

    @Override
    public void run(ApplicationArguments args) {
        bootstrap();
    }

    /** Separated from {@link #run} so a test can drive it without restarting a context. */
    @Transactional
    public Outcome bootstrap() {
        String email = properties.email();

        if (hasAdministrator()) {
            log.debug("an administrator already exists; leaving accounts alone");
            return new Outcome(Result.ALREADY_PRESENT, email, false);
        }

        Map<String, Object> existing = jdbc
                .sql("SELECT id, is_guest FROM app_user WHERE email = :email AND deleted_at IS NULL")
                .param("email", email)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);

        if (existing != null) {
            if (Boolean.TRUE.equals(existing.get("is_guest"))) {
                // The CHECK constraint would refuse this anyway; saying so beats a
                // constraint-violation stack trace at startup.
                log.error("noveltea.admin.email ({}) belongs to a guest account, which cannot "
                        + "be an administrator. This instance has no administrator: point "
                        + "NOVELTEA_ADMIN_EMAIL at a different address.", email);
                return new Outcome(Result.REFUSED_GUEST, email, false);
            }
            jdbc.sql("UPDATE app_user SET is_admin = true, updated_at = now() WHERE id = :id")
                    .param("id", existing.get("id"))
                    .update();
            log.warn("No administrator existed. Promoted the existing account {} to "
                    + "administrator. Its password is unchanged.", email);
            return new Outcome(Result.PROMOTED, email, false);
        }

        // The provenance of the password does not matter here, only its strength. An
        // operator who sets a real one gets an account they can use straight away; the
        // built-in default is five characters and therefore cannot survive first sign-in.
        boolean mustChange = !Passwords.isStrongEnough(properties.password());
        try {
            jdbc.sql("""
                    INSERT INTO app_user (id, email, display_name, password_hash, is_admin,
                                          must_change_password)
                    VALUES (:id, :email, 'Administrator', :hash, true, :mustChange)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("email", email)
                    .param("hash", passwords.encode(properties.password()))
                    .param("mustChange", mustChange)
                    .update();
        } catch (DuplicateKeyException e) {
            // Two replicas started together and the other one won. Both wanted the same
            // outcome, and it happened.
            log.info("another instance created the administrator account first");
            return new Outcome(Result.ALREADY_PRESENT, email, false);
        }

        if (mustChange) {
            log.warn("""

                    ==========================================================
                     First run: created the administrator account {}.
                     Sign in with the default password and change it - until
                     you do, this account can do nothing else.
                     Set NOVELTEA_ADMIN_EMAIL / NOVELTEA_ADMIN_PASSWORD to
                     choose your own before first start.
                    ==========================================================""", email);
        } else {
            log.info("First run: created the administrator account {} with the configured "
                    + "password.", email);
        }
        return new Outcome(Result.CREATED, email, mustChange);
    }

    private boolean hasAdministrator() {
        return Boolean.TRUE.equals(jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM app_user WHERE is_admin AND deleted_at IS NULL)")
                .query(Boolean.class)
                .single());
    }
}

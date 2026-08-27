package com.noveltea.account;

import com.noveltea.account.AccountExceptions.NoDeletionPending;
import com.noveltea.auth.AuthExceptions.InvalidCredentials;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.auth.Passwords;
import com.noveltea.auth.TokenService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset and account deletion.
 *
 * <p>Both are the paths an attacker reaches for and the paths a distressed user reaches
 * for, so both are deliberately unhelpful about what exists and deliberately slow to
 * destroy anything.
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final JdbcClient jdbc;
    private final TokenService tokens;
    private final PasswordEncoder passwords;
    private final AccountProperties properties;
    private final PasswordResetDelivery delivery;
    private final AuthService auth;

    public AccountService(
            JdbcClient jdbc,
            TokenService tokens,
            PasswordEncoder passwords,
            AccountProperties properties,
            PasswordResetDelivery delivery,
            AuthService auth) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.passwords = passwords;
        this.properties = properties;
        this.delivery = delivery;
        this.auth = auth;
    }

    public record DeletionStatus(
            boolean pending, OffsetDateTime requestedAt, OffsetDateTime scheduledFor) {}

    /**
     * @param session a replacement token pair for the device that made the change
     * @param devicesSignedOut how many <em>other</em> devices were signed out
     */
    public record PasswordChange(Session session, int devicesSignedOut) {}

    // ------------------------------------------------------- password change

    /**
     * Changes your own password, knowing the current one.
     *
     * <p>Distinct from the emailed reset below, and not a convenience duplicate of it. The
     * reset assumes you cannot prove who you are and so needs a second channel; this needs
     * no mail server at all, which is what makes it the path a freshly bootstrapped
     * instance can actually walk. It is also the only route
     * {@code PasswordChangeRequiredFilter} lets a locked account reach.
     *
     * <p>Signs out every <em>other</em> device, where the reset signs out all of them. The
     * difference follows from what each one means: somebody completing a reset usually
     * believes they were compromised, while somebody typing their current password is
     * standing at a device they trust, and throwing them out of it as a reward for good
     * hygiene teaches people not to bother.
     */
    @Transactional
    public PasswordChange changePassword(
            UUID userId, UUID deviceId, String currentPassword, String newPassword) {
        Objects.requireNonNull(userId, "userId");
        if (deviceId == null) {
            // Every token this API issues names its device. One that does not cannot be
            // reissued, and is not something to guess about.
            throw new InvalidCredentials("invalid credentials");
        }

        String hash = jdbc.sql("""
                SELECT password_hash FROM app_user
                 WHERE id = :id AND deleted_at IS NULL AND is_guest = false
                """)
                .param("id", userId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new InvalidCredentials("invalid credentials"));

        if (currentPassword == null || !passwords.matches(currentPassword, hash)) {
            throw new InvalidCredentials("invalid credentials");
        }
        Passwords.require(newPassword);
        if (newPassword.equals(currentPassword)) {
            // Worth its own message: an account that got here because somebody else chose
            // its password could otherwise satisfy the requirement by keeping it.
            throw new IllegalArgumentException("the new password must be different");
        }

        jdbc.sql("""
                UPDATE app_user
                   SET password_hash = :hash, must_change_password = false, updated_at = now()
                 WHERE id = :id
                """)
                .param("hash", passwords.encode(newPassword))
                .param("id", userId)
                .update();

        int signedOut = jdbc.sql("""
                UPDATE device SET revoked_at = now(), refresh_token_hash = NULL
                 WHERE user_id = :userId AND id <> :deviceId AND revoked_at IS NULL
                """)
                .param("userId", userId)
                .param("deviceId", deviceId)
                .update();

        // After the flag is cleared, so the replacement token is minted without the claim
        // that was holding this account at the door.
        Session session = auth.reissueSession(userId, deviceId);
        log.info("password changed by its owner; {} other devices signed out", signedOut);
        return new PasswordChange(session, signedOut);
    }

    // -------------------------------------------------------- password reset

    /**
     * Starts a reset.
     *
     * <p>Returns nothing and reveals nothing. Telling a caller whether an address is
     * registered turns this into the account-enumeration oracle that login deliberately is
     * not, and this endpoint needs no authentication to reach.
     */
    @Transactional
    public void requestReset(String email, String requestIp) {
        if (email == null || email.isBlank()) {
            return;
        }
        Optional<UUID> userId = jdbc.sql("""
                SELECT id FROM app_user
                 WHERE email = :email AND deleted_at IS NULL AND is_guest = false
                """)
                .param("email", email.trim())
                .query(UUID.class)
                .optional();

        if (userId.isEmpty()) {
            log.info("password reset requested for an address that is not registered");
            return;
        }

        // Any earlier link stops working: two live reset tokens double the window in which
        // one can be stolen, for no benefit.
        jdbc.sql("UPDATE password_reset SET consumed_at = now() WHERE user_id = :id AND consumed_at IS NULL")
                .param("id", userId.get()).update();

        String token = tokens.generateRefreshToken();
        jdbc.sql("""
                INSERT INTO password_reset (user_id, token_hash, requested_ip, expires_at)
                VALUES (:userId, :hash, :ip, :expiresAt)
                """)
                .param("userId", userId.get())
                .param("hash", tokens.hash(token))
                .param("ip", requestIp)
                .param("expiresAt", OffsetDateTime.now().plus(properties.passwordResetTtl()))
                .update();

        delivery.deliver(email.trim(), token);
    }

    /**
     * Completes a reset and signs every device out.
     *
     * <p>Revoking devices is the point, not a side effect: someone resetting a password
     * usually believes it was compromised, and leaving the attacker's paired phone signed
     * in would make the reset theatre.
     *
     * @return how many devices were signed out
     */
    @Transactional
    public int confirmReset(String token, String newPassword) {
        if (token == null || token.isBlank()) {
            throw new InvalidCredentials("invalid or expired reset link");
        }
        Passwords.require(newPassword);

        Map<String, Object> row = jdbc.sql("""
                SELECT id, user_id FROM password_reset
                 WHERE token_hash = :hash AND consumed_at IS NULL AND expires_at > now()
                 FOR UPDATE
                """)
                .param("hash", tokens.hash(token))
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCredentials("invalid or expired reset link"));

        UUID userId = (UUID) row.get("user_id");

        int consumed = jdbc.sql("UPDATE password_reset SET consumed_at = now() WHERE id = :id AND consumed_at IS NULL")
                .param("id", row.get("id")).update();
        if (consumed != 1) {
            throw new InvalidCredentials("invalid or expired reset link");
        }

        jdbc.sql("UPDATE app_user SET password_hash = :hash, updated_at = now() WHERE id = :id")
                .param("hash", passwords.encode(newPassword)).param("id", userId).update();

        int signedOut = jdbc.sql("""
                UPDATE device SET revoked_at = now(), refresh_token_hash = NULL
                 WHERE user_id = :userId AND revoked_at IS NULL
                """)
                .param("userId", userId).update();

        log.info("password reset completed; {} devices signed out", signedOut);
        return signedOut;
    }

    // ------------------------------------------------------ account deletion

    /**
     * Schedules deletion after a grace period.
     *
     * <p>Requires the current password even though the caller is already authenticated: a
     * borrowed unlocked laptop should not be able to destroy someone's novels.
     */
    @Transactional
    public DeletionStatus requestDeletion(UUID userId, String currentPassword) {
        Objects.requireNonNull(userId, "userId");
        String hash = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id AND deleted_at IS NULL")
                .param("id", userId).query(String.class).optional()
                .orElseThrow(() -> new InvalidCredentials("invalid credentials"));

        if (currentPassword == null || !passwords.matches(currentPassword, hash)) {
            throw new InvalidCredentials("invalid credentials");
        }

        jdbc.sql("UPDATE app_user SET deletion_requested_at = now() WHERE id = :id")
                .param("id", userId).update();
        return status(userId);
    }

    @Transactional
    public void cancelDeletion(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        int updated = jdbc.sql("""
                UPDATE app_user SET deletion_requested_at = NULL
                 WHERE id = :id AND deletion_requested_at IS NOT NULL AND deleted_at IS NULL
                """)
                .param("id", userId).update();
        if (updated == 0) {
            throw new NoDeletionPending();
        }
    }

    public DeletionStatus status(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        OffsetDateTime requestedAt = jdbc
                .sql("SELECT deletion_requested_at FROM app_user WHERE id = :id")
                .param("id", userId)
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);

        return requestedAt == null
                ? new DeletionStatus(false, null, null)
                : new DeletionStatus(true, requestedAt, requestedAt.plus(properties.deletionGrace()));
    }

    /**
     * Carries out deletions whose grace period has elapsed.
     *
     * <p>Called by the retention sweep. Removing the {@code app_user} row cascades to every
     * project, and from there to every document — which is the point, and why nothing here
     * runs until the grace period is genuinely over.
     */
    @Transactional
    public int purgeDueDeletions() {
        var due = jdbc.sql("""
                SELECT id FROM app_user
                 WHERE deletion_requested_at IS NOT NULL
                   AND deleted_at IS NULL
                   AND deletion_requested_at < now() - CAST(:grace AS interval)
                """)
                .param("grace", properties.deletionGrace().toSeconds() + " seconds")
                .query(UUID.class)
                .list();

        for (UUID userId : due) {
            // project.owner_id is ON DELETE RESTRICT on purpose: a stray user delete must
            // never quietly take a novel with it. That safety means deletion has to say
            // explicitly that it means the projects too — which is exactly the intent here,
            // and only reachable after the grace period has run out.
            int projects = jdbc.sql("DELETE FROM project WHERE owner_id = :id")
                    .param("id", userId).update();
            jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", userId).update();
            log.info("account {} deleted after its grace period, with {} projects", userId, projects);
        }
        return due.size();
    }

}

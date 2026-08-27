package com.noveltea.admin;

import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthExceptions.EmailAlreadyRegistered;
import com.noveltea.auth.Passwords;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What an instance administrator can do that an ordinary account cannot.
 *
 * <p>Which is deliberately very little: list the accounts, make one, and set a password
 * for somebody who has lost theirs. An admin is an operator of a server, not a reader of
 * other people's novels — nothing here touches a project, a binder item or a document, and
 * being an admin grants no access to any of them. {@code ProjectAccess} remains the only
 * thing that decides who may read a manuscript, and it has never heard of this class.
 *
 * <p>The password-setting path exists because of what the instance defaults add up to:
 * self-registration is closed, and {@code spring.mail.host} is usually unset on a home
 * server, which means the emailed reset writes its link to a log file the account holder
 * cannot read. Without an admin who can set a password, the second person to use a NovelTea
 * instance is one forgotten password away from being locked out permanently.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;

    public AdminService(JdbcClient jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
    }

    public record UserSummary(
            UUID id,
            String email,
            String displayName,
            boolean admin,
            boolean guest,
            boolean mustChangePassword,
            OffsetDateTime createdAt,
            OffsetDateTime deletionRequestedAt) {}

    /**
     * @param password present exactly once, in the response to the request that set it.
     *     Only its hash is stored, so there is no second chance to read it.
     */
    public record NewAccount(UUID id, String email, String password) {}

    // -------------------------------------------------------- authorization

    /**
     * Throws unless this caller administers the instance.
     *
     * <p>{@link AccessDenied} maps to 404, not 403 — the same rule the rest of the API
     * follows. A 403 here would confirm to any signed-in account that an administration
     * API exists and that somebody holds it.
     */
    public void requireAdmin(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        boolean admin = Boolean.TRUE.equals(jdbc
                .sql("SELECT is_admin FROM app_user WHERE id = :id AND deleted_at IS NULL")
                .param("id", userId)
                .query(Boolean.class)
                .optional()
                .orElse(false));
        if (!admin) {
            throw new AccessDenied("not an administrator");
        }
    }

    // --------------------------------------------------------------- people

    public List<UserSummary> listUsers(UUID actingAdminId) {
        requireAdmin(actingAdminId);
        return jdbc.sql("""
                -- email is citext, which the driver returns as a PGobject rather than a
                -- String. Cast in SQL so the row map holds what its reader expects.
                SELECT id, email::text AS email, display_name, is_admin, is_guest,
                       must_change_password,
                       created_at, deletion_requested_at
                  FROM app_user
                 WHERE deleted_at IS NULL
                 ORDER BY created_at
                """)
                .query()
                .listOfRows()
                .stream()
                .map(r -> new UserSummary(
                        (UUID) r.get("id"),
                        (String) r.get("email"),
                        (String) r.get("display_name"),
                        Boolean.TRUE.equals(r.get("is_admin")),
                        Boolean.TRUE.equals(r.get("is_guest")),
                        Boolean.TRUE.equals(r.get("must_change_password")),
                        toOffset(r.get("created_at")),
                        toOffset(r.get("deletion_requested_at"))))
                .toList();
    }

    /**
     * Creates an account somebody else will use.
     *
     * <p>Always with {@code must_change_password}, whatever the password's strength: the
     * point is not that the password is weak, it is that <em>the admin knows it</em>. An
     * account whose operator can read its documents is not the account holder's, and the
     * only moment that stops being true is when they choose a password nobody else has
     * seen.
     *
     * @param password may be null or blank, in which case one is generated and returned
     */
    @Transactional
    public NewAccount createUser(
            UUID actingAdminId, String email, String password, String displayName, boolean admin) {
        requireAdmin(actingAdminId);
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be null or blank");
        }
        // A supplied password still has to be a real one. The admin is choosing something
        // a person will type at least once, and "changeme" travelling over chat is worse
        // than a generated string nobody is tempted to keep.
        String resolved = password == null || password.isBlank() ? Passwords.generate() : password;
        Passwords.require(resolved);

        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO app_user (id, email, display_name, password_hash, is_admin,
                                          must_change_password)
                    VALUES (:id, :email, :displayName, :hash, :admin, true)
                    """)
                    .param("id", id)
                    .param("email", email.trim())
                    .param("displayName", displayName == null || displayName.isBlank()
                            ? null : displayName.trim())
                    .param("hash", passwords.encode(resolved))
                    .param("admin", admin)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegistered(email);
        }
        log.info("administrator {} created account {} (admin={})", actingAdminId, id, admin);
        return new NewAccount(id, email.trim(), resolved);
    }

    /**
     * Sets a password for an account whose holder cannot.
     *
     * <p>Revokes every device, for the same reason completing an emailed reset does: the
     * account is being handed over, and whatever was signed in before this moment was
     * signed in as somebody else. It is also what closes the one gap in carrying
     * {@code must_change_password} as a token claim — a device that cannot refresh cannot
     * outlive the fifteen minutes its current access token has left.
     */
    @Transactional
    public NewAccount resetPassword(UUID actingAdminId, UUID targetUserId, String password) {
        requireAdmin(actingAdminId);
        Objects.requireNonNull(targetUserId, "userId");

        String email = jdbc.sql("""
                SELECT email FROM app_user
                 WHERE id = :id AND deleted_at IS NULL AND is_guest = false
                """)
                .param("id", targetUserId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AccessDenied("no such account"));

        String resolved = password == null || password.isBlank() ? Passwords.generate() : password;
        Passwords.require(resolved);

        jdbc.sql("""
                UPDATE app_user
                   SET password_hash = :hash, must_change_password = true, updated_at = now()
                 WHERE id = :id
                """)
                .param("hash", passwords.encode(resolved))
                .param("id", targetUserId)
                .update();

        int signedOut = jdbc.sql("""
                UPDATE device SET revoked_at = now(), refresh_token_hash = NULL
                 WHERE user_id = :id AND revoked_at IS NULL
                """)
                .param("id", targetUserId)
                .update();

        log.info("administrator {} set a new password for account {}; {} devices signed out",
                actingAdminId, targetUserId, signedOut);
        return new NewAccount(targetUserId, email, resolved);
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}

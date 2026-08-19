package com.noveltea.auth;

import com.noveltea.auth.AuthExceptions.EmailAlreadyRegistered;
import com.noveltea.auth.AuthExceptions.InvalidCredentials;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, sign-in, device pairing and token rotation.
 *
 * <p>Every failure path returns the same {@link InvalidCredentials} message. Telling a
 * caller whether the email exists, the password was wrong, or the code had expired turns
 * this into an account-enumeration oracle.
 */
@Service
public class AuthService {

    private static final String GENERIC_FAILURE = "invalid credentials";
    private static final List<String> PLATFORMS = List.of("web", "windows", "macos", "ios");

    private final JdbcClient jdbc;
    private final TokenService tokens;
    private final PasswordEncoder passwords;

    public AuthService(JdbcClient jdbc, TokenService tokens, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.tokens = tokens;
        this.passwords = passwords;
    }

    public record Session(
            UUID userId, UUID deviceId, String accessToken, String refreshToken, long expiresInSeconds) {}

    public record DeviceInfo(
            UUID id, String name, String platform, OffsetDateTime createdAt,
            OffsetDateTime lastSeenAt, OffsetDateTime lastSyncedAt, boolean current) {}

    // ------------------------------------------------------------ registration

    @Transactional
    public Session register(String email, String password, String deviceName, String platform) {
        requireText(email, "email");
        requirePassword(password);
        UUID userId = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO app_user (id, email, password_hash) VALUES (:id, :email, :hash)")
                    .param("id", userId)
                    .param("email", email.trim())
                    .param("hash", passwords.encode(password))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new EmailAlreadyRegistered(email);
        }
        return createDeviceSession(userId, deviceName, platform);
    }

    @Transactional
    public Session login(String email, String password, String deviceName, String platform) {
        requireText(email, "email");
        Map<String, Object> user = jdbc
                .sql("SELECT id, password_hash, is_guest FROM app_user WHERE email = :email")
                .param("email", email == null ? "" : email.trim())
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(null);

        String hash = user == null ? null : (String) user.get("password_hash");
        // Hash even when the user is absent, so a missing account and a wrong password
        // take comparable time and cannot be distinguished by a stopwatch.
        boolean ok = passwords.matches(password == null ? "" : password,
                hash == null ? "$2a$10$invalidinvalidinvalidinvalidinvalidinvalidinvalidinvalidin" : hash);
        if (user == null || hash == null || !ok) {
            throw new InvalidCredentials(GENERIC_FAILURE);
        }
        return createDeviceSession((UUID) user.get("id"), deviceName, platform);
    }

    // --------------------------------------------------------------- pairing

    /** Called by an already-trusted device. Returns the plaintext code exactly once. */
    @Transactional
    public String createPairingCode(UUID userId, UUID requestingDeviceId) {
        Objects.requireNonNull(userId, "userId");
        String code = tokens.generatePairingCode();
        jdbc.sql("""
                INSERT INTO pairing_code (user_id, code_hash, created_by_device_id, expires_at)
                VALUES (:userId, :hash, :deviceId, :expiresAt)
                """)
                .param("userId", userId)
                .param("hash", tokens.hash(normaliseCode(code)))
                .param("deviceId", requestingDeviceId)
                .param("expiresAt", OffsetDateTime.now().plus(tokens.pairingCodeTtl()))
                .update();
        return code;
    }

    /** Redeems a code on a new device. Single use, and expiry is enforced in SQL. */
    @Transactional
    public Session pair(String code, String deviceName, String platform) {
        if (code == null || code.isBlank()) {
            throw new InvalidCredentials(GENERIC_FAILURE);
        }
        Map<String, Object> row = jdbc.sql("""
                SELECT id, user_id FROM pairing_code
                 WHERE code_hash = :hash AND consumed_at IS NULL AND expires_at > now()
                 FOR UPDATE
                """)
                .param("hash", tokens.hash(normaliseCode(code)))
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCredentials(GENERIC_FAILURE));

        UUID userId = (UUID) row.get("user_id");
        Session session = createDeviceSession(userId, deviceName, platform);

        int consumed = jdbc.sql("""
                UPDATE pairing_code SET consumed_at = now(), consumed_by_device_id = :deviceId
                 WHERE id = :id AND consumed_at IS NULL
                """)
                .param("deviceId", session.deviceId())
                .param("id", row.get("id"))
                .update();
        if (consumed != 1) {
            // Another request redeemed it between the SELECT and here.
            throw new InvalidCredentials(GENERIC_FAILURE);
        }
        return session;
    }

    // ---------------------------------------------------------------- tokens

    /**
     * Exchanges a refresh token for a new pair, rotating the stored token.
     *
     * <p>Rotation is what limits the damage of a leaked token: it works at most once, and
     * the legitimate device's next refresh fails, which is a detectable signal.
     */
    @Transactional
    public Session refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidCredentials(GENERIC_FAILURE);
        }
        Map<String, Object> device = jdbc.sql("""
                SELECT id, user_id FROM device
                 WHERE refresh_token_hash = :hash
                   AND revoked_at IS NULL
                   AND (refresh_token_expires_at IS NULL OR refresh_token_expires_at > now())
                 FOR UPDATE
                """)
                .param("hash", tokens.hash(refreshToken))
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new InvalidCredentials(GENERIC_FAILURE));

        UUID deviceId = (UUID) device.get("id");
        UUID userId = (UUID) device.get("user_id");
        String rotated = tokens.generateRefreshToken();
        storeRefreshToken(deviceId, rotated);

        return new Session(userId, deviceId, tokens.issueAccessToken(userId, deviceId), rotated,
                tokens.accessTokenTtl().toSeconds());
    }

    // --------------------------------------------------------------- devices

    public List<DeviceInfo> listDevices(UUID userId, UUID currentDeviceId) {
        Objects.requireNonNull(userId, "userId");
        return jdbc.sql("""
                SELECT id, name, platform, created_at, last_seen_at, last_synced_at
                  FROM device WHERE user_id = :userId AND revoked_at IS NULL
                 ORDER BY created_at
                """)
                .param("userId", userId)
                .query()
                .listOfRows()
                .stream()
                .map(r -> new DeviceInfo(
                        (UUID) r.get("id"),
                        (String) r.get("name"),
                        (String) r.get("platform"),
                        toOffset(r.get("created_at")),
                        toOffset(r.get("last_seen_at")),
                        toOffset(r.get("last_synced_at")),
                        r.get("id").equals(currentDeviceId)))
                .toList();
    }

    /** Revoking clears the refresh token, so the device cannot renew once its access token lapses. */
    @Transactional
    public void revokeDevice(UUID userId, UUID deviceId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(deviceId, "deviceId");
        int updated = jdbc.sql("""
                UPDATE device SET revoked_at = now(), refresh_token_hash = NULL
                 WHERE id = :deviceId AND user_id = :userId AND revoked_at IS NULL
                """)
                .param("deviceId", deviceId)
                .param("userId", userId)
                .update();
        if (updated == 0) {
            throw new AuthExceptions.AccessDenied("device not found for this account");
        }
    }

    public void touch(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        jdbc.sql("UPDATE device SET last_seen_at = now() WHERE id = :id")
                .param("id", deviceId).update();
    }

    // -------------------------------------------------------------- internals

    private Session createDeviceSession(UUID userId, String deviceName, String platform) {
        String resolvedPlatform = PLATFORMS.contains(platform) ? platform : "web";
        UUID deviceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO device (id, user_id, name, platform, last_seen_at)
                VALUES (:id, :userId, :name, :platform, now())
                """)
                .param("id", deviceId)
                .param("userId", userId)
                .param("name", deviceName == null || deviceName.isBlank() ? "Unnamed device" : deviceName.trim())
                .param("platform", resolvedPlatform)
                .update();

        String refreshToken = tokens.generateRefreshToken();
        storeRefreshToken(deviceId, refreshToken);
        return new Session(userId, deviceId, tokens.issueAccessToken(userId, deviceId), refreshToken,
                tokens.accessTokenTtl().toSeconds());
    }

    private void storeRefreshToken(UUID deviceId, String refreshToken) {
        jdbc.sql("""
                UPDATE device
                   SET refresh_token_hash = :hash,
                       refresh_token_issued_at = now(),
                       refresh_token_expires_at = :expiresAt
                 WHERE id = :id
                """)
                .param("hash", tokens.hash(refreshToken))
                .param("expiresAt", OffsetDateTime.ofInstant(
                        Instant.now().plus(tokens.refreshTokenTtl()), java.time.ZoneOffset.UTC))
                .param("id", deviceId)
                .update();
    }

    /** Codes are shown grouped and read aloud; accept any casing or spacing. */
    private static String normaliseCode(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
    }

    private static void requirePassword(String password) {
        if (password == null || password.length() < 12) {
            throw new IllegalArgumentException("password must be at least 12 characters");
        }
    }
}

package com.noveltea.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies credentials.
 *
 * <p>Access tokens are short-lived signed JWTs carrying the user and device. Refresh
 * tokens are opaque random strings — only their SHA-256 hash is stored, so a database
 * dump cannot be replayed as a login, and they rotate on every use so a stolen token is
 * usable at most once before the legitimate device invalidates it.
 */
@Service
public class TokenService {

    public static final String DEVICE_CLAIM = "did";

    /**
     * Present, and true, only while the holder still has to choose a password.
     *
     * <p>In the token rather than read from the database on every request: the filter
     * chain is stateless by design and this is the hot path for sync. The cost is that a
     * token minted before the flag was set does not carry it, which is why every path
     * that sets {@code must_change_password} on an existing account also revokes that
     * account's devices — a revoked device cannot refresh, so the stale token dies with
     * its fifteen-minute lifetime and nothing can be issued to replace it.
     */
    public static final String PASSWORD_CHANGE_CLAIM = "pwc";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    /** Excludes I, L, O, 0, 1 — a pairing code gets read aloud and typed by a human. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final AuthProperties properties;

    public TokenService(AuthProperties properties) {
        byte[] secret = decodeSecret(properties.jwtSecret());
        SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.properties = properties;
    }

    private static byte[] decodeSecret(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "noveltea.auth.jwt-secret is not set. Refusing to start rather than "
                            + "invent a signing key that would silently reach production.");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException e) {
            secret = configured.getBytes(StandardCharsets.UTF_8);
        }
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "noveltea.auth.jwt-secret must be at least 32 bytes; got " + secret.length);
        }
        return secret;
    }

    public String issueAccessToken(UUID userId, UUID deviceId) {
        return issueAccessToken(userId, deviceId, false);
    }

    public String issueAccessToken(UUID userId, UUID deviceId, boolean mustChangePassword) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("noveltea")
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(userId.toString())
                .claim(DEVICE_CLAIM, deviceId.toString());
        // Added only when true, so an ordinary token is byte-for-byte what it always was.
        if (mustChangePassword) {
            claimsBuilder.claim(PASSWORD_CHANGE_CLAIM, true);
        }
        JwtClaimsSet claims = claimsBuilder.build();
        // The header must name HS256 explicitly: the encoder defaults to RS256 and would
        // fail to select our symmetric key ("Failed to select a JWK signing key").
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Jwt verifyAccessToken(String token) {
        return decoder.decode(token);
    }

    /** Opaque, 256 bits of entropy. Returned once; only the hash is ever stored. */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL64.encodeToString(bytes);
    }

    public String generatePairingCode() {
        StringBuilder code = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                code.append('-');
            }
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    /** SHA-256. These are high-entropy random values, so no work factor is needed. */
    public String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder()
                    .encodeToString(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public java.time.Duration refreshTokenTtl() {
        return properties.refreshTokenTtl();
    }

    public java.time.Duration pairingCodeTtl() {
        return properties.pairingCodeTtl();
    }

    public java.time.Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }
}

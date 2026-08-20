package com.noveltea.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthExceptions.EmailAlreadyRegistered;
import com.noveltea.auth.AuthExceptions.InvalidCredentials;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuthServiceTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired AuthService auth;
    @Autowired TokenService tokens;
    @Autowired ProjectAccess access;

    private String email() {
        return "author-" + UUID.randomUUID() + "@example.com";
    }

    private String storedRefreshHash(UUID deviceId) {
        return jdbc.sql("SELECT refresh_token_hash FROM device WHERE id = :id")
                .param("id", deviceId).query(String.class).optional().orElse(null);
    }

    // ------------------------------------------------------------ accounts

    @Test
    @DisplayName("registering yields a working session and never stores the password")
    void registerCreatesSessionAndHashesPassword() {
        String email = email();
        Session session = auth.register(email, PASSWORD, "Laptop", "web");

        assertThat(session.userId()).isNotNull();
        assertThat(session.deviceId()).isNotNull();
        assertThat(session.refreshToken()).isNotBlank();

        String stored = jdbc.sql("SELECT password_hash FROM app_user WHERE id = :id")
                .param("id", session.userId()).query(String.class).single();
        assertThat(stored).doesNotContain(PASSWORD).startsWith("$2");

        var jwt = tokens.verifyAccessToken(session.accessToken());
        assertThat(jwt.getSubject()).isEqualTo(session.userId().toString());
        assertThat(jwt.getClaimAsString(TokenService.DEVICE_CLAIM))
                .isEqualTo(session.deviceId().toString());
    }

    @Test
    @DisplayName("the refresh token is stored only as a hash")
    void refreshTokenIsNotStoredInPlaintext() {
        Session session = auth.register(email(), PASSWORD, "Laptop", "web");
        String stored = storedRefreshHash(session.deviceId());

        assertThat(stored).isNotEqualTo(session.refreshToken());
        assertThat(stored).isEqualTo(tokens.hash(session.refreshToken()));
    }

    @Test
    @DisplayName("a duplicate email is rejected")
    void duplicateEmailRejected() {
        String email = email();
        auth.register(email, PASSWORD, "Laptop", "web");
        assertThatThrownBy(() -> auth.register(email, PASSWORD, "Other", "web"))
                .isInstanceOf(EmailAlreadyRegistered.class);
    }

    @Test
    @DisplayName("short passwords are refused")
    void shortPasswordRefused() {
        assertThatThrownBy(() -> auth.register(email(), "short", "Laptop", "web"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("wrong password and unknown account fail identically — no enumeration oracle")
    void failuresAreIndistinguishable() {
        String email = email();
        auth.register(email, PASSWORD, "Laptop", "web");

        String wrongPassword = catchMessage(() -> auth.login(email, "not the password", "d", "web"));
        String unknownAccount = catchMessage(() -> auth.login(email(), PASSWORD, "d", "web"));

        assertThat(wrongPassword)
                .as("a differing message tells an attacker which emails are registered")
                .isEqualTo(unknownAccount);
    }

    @Test
    @DisplayName("logging in creates a second device without disturbing the first")
    void loginCreatesAdditionalDevice() {
        String email = email();
        Session first = auth.register(email, PASSWORD, "Laptop", "web");
        Session second = auth.login(email, PASSWORD, "Phone", "ios");

        assertThat(second.userId()).isEqualTo(first.userId());
        assertThat(second.deviceId()).isNotEqualTo(first.deviceId());
        assertThat(storedRefreshHash(first.deviceId()))
                .as("signing in elsewhere must not log the first device out")
                .isEqualTo(tokens.hash(first.refreshToken()));
        assertThat(auth.listDevices(first.userId(), first.deviceId())).hasSize(2);
    }

    // -------------------------------------------------------------- rotation

    @Test
    @DisplayName("refreshing rotates the token and invalidates the old one")
    void refreshRotates() {
        Session original = auth.register(email(), PASSWORD, "Laptop", "web");

        Session rotated = auth.refresh(original.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(original.refreshToken());
        assertThat(rotated.deviceId()).isEqualTo(original.deviceId());
        assertThatThrownBy(() -> auth.refresh(original.refreshToken()))
                .as("a stolen token must be usable at most once")
                .isInstanceOf(InvalidCredentials.class);
        assertThat(auth.refresh(rotated.refreshToken())).isNotNull();
    }

    @Test
    @DisplayName("a garbage refresh token is rejected")
    void garbageRefreshRejected() {
        assertThatThrownBy(() -> auth.refresh("not-a-real-token"))
                .isInstanceOf(InvalidCredentials.class);
        assertThatThrownBy(() -> auth.refresh(null)).isInstanceOf(InvalidCredentials.class);
    }

    // --------------------------------------------------------------- pairing

    @Test
    @DisplayName("a pairing code moves an account onto a second device")
    void pairingCodeOnboardsANewDevice() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());

        Session phone = auth.pair(code, "Phone", "ios");

        assertThat(phone.userId()).isEqualTo(laptop.userId());
        assertThat(phone.deviceId()).isNotEqualTo(laptop.deviceId());
        assertThat(auth.listDevices(laptop.userId(), laptop.deviceId())).hasSize(2);
    }

    @Test
    @DisplayName("a pairing code works exactly once")
    void pairingCodeIsSingleUse() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());
        auth.pair(code, "Phone", "ios");

        assertThatThrownBy(() -> auth.pair(code, "Tablet", "ios"))
                .isInstanceOf(InvalidCredentials.class);
        assertThat(auth.listDevices(laptop.userId(), laptop.deviceId())).hasSize(2);
    }

    @Test
    @DisplayName("codes are accepted regardless of case or spacing, but not when wrong")
    void pairingCodeNormalisation() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());

        Session paired = auth.pair(code.toLowerCase().replace("-", " "), "Phone", "ios");
        assertThat(paired.userId()).isEqualTo(laptop.userId());

        assertThatThrownBy(() -> auth.pair("ZZZZ-ZZZZ", "Nope", "web"))
                .isInstanceOf(InvalidCredentials.class);
    }

    @Test
    @DisplayName("an expired pairing code is refused")
    void expiredPairingCodeRefused() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());

        jdbc.sql("UPDATE pairing_code SET expires_at = now() - interval '1 minute'").update();

        assertThatThrownBy(() -> auth.pair(code, "Phone", "ios"))
                .isInstanceOf(InvalidCredentials.class);
    }

    @Test
    @DisplayName("pairing codes are stored hashed")
    void pairingCodesAreHashed() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());

        String stored = jdbc.sql("SELECT code_hash FROM pairing_code").query(String.class).single();
        assertThat(stored).isNotEqualTo(code).isEqualTo(tokens.hash(code.replace("-", "")));
    }

    // -------------------------------------------------------------- devices

    @Test
    @DisplayName("revoking a device stops it refreshing and hides it from the list")
    void revokeStopsRefresh() {
        Session laptop = auth.register(email(), PASSWORD, "Laptop", "web");
        String code = auth.createPairingCode(laptop.userId(), laptop.deviceId());
        Session phone = auth.pair(code, "Phone", "ios");

        auth.revokeDevice(laptop.userId(), phone.deviceId());

        assertThatThrownBy(() -> auth.refresh(phone.refreshToken()))
                .isInstanceOf(InvalidCredentials.class);
        assertThat(auth.listDevices(laptop.userId(), laptop.deviceId()))
                .singleElement()
                .satisfies(d -> assertThat(d.id()).isEqualTo(laptop.deviceId()));
    }

    @Test
    @DisplayName("one account cannot revoke another account's device")
    void cannotRevokeSomeoneElsesDevice() {
        Session mine = auth.register(email(), PASSWORD, "Mine", "web");
        Session theirs = auth.register(email(), PASSWORD, "Theirs", "web");

        assertThatThrownBy(() -> auth.revokeDevice(mine.userId(), theirs.deviceId()))
                .isInstanceOf(AccessDenied.class);
        assertThat(storedRefreshHash(theirs.deviceId())).isNotNull();
    }

    // -------------------------------------------------------- authorization

    @Test
    @DisplayName("a project is invisible to anyone but its owner")
    void projectsAreOwnerOnly() {
        Session owner = auth.register(email(), PASSWORD, "Owner", "web");
        Session stranger = auth.register(email(), PASSWORD, "Stranger", "web");

        UUID owned = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :owner, 'Secret Novel')")
                .param("id", owned).param("owner", owner.userId()).update();

        CurrentUser ownerPrincipal = new CurrentUser(owner.userId(), owner.deviceId());
        CurrentUser strangerPrincipal = new CurrentUser(stranger.userId(), stranger.deviceId());

        access.requireReadable(ownerPrincipal, owned);
        assertThatThrownBy(() -> access.requireReadable(strangerPrincipal, owned))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> access.requireWritable(strangerPrincipal, owned))
                .isInstanceOf(AccessDenied.class);
    }

    @Test
    @DisplayName("an unauthenticated principal is refused outright")
    void anonymousIsRefused() {
        assertThatThrownBy(() -> access.requireReadable(null, projectId))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> access.requireReadable(new CurrentUser(null, null), projectId))
                .isInstanceOf(AccessDenied.class);
    }

    private static String catchMessage(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected a failure");
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    @Test
    @DisplayName("an unknown account costs a real hash, so timing does not reveal it")
    void unknownAccountStillHashes() {
        String email = email();
        auth.register(email, PASSWORD, "Laptop", "web");

        // The identical error message is only half the defence. If bcrypt is skipped when
        // no account exists, a stopwatch distinguishes registered addresses from unknown
        // ones just as reliably as a different message would.
        long knownNanos = timeFailedLogin(email);
        long unknownNanos = timeFailedLogin("absent-" + UUID.randomUUID() + "@example.com");

        double ratio = (double) Math.max(knownNanos, unknownNanos)
                / Math.max(1, Math.min(knownNanos, unknownNanos));
        assertThat(ratio)
                .as("a wrong password took %dms and an unknown address %dms — bcrypt is being skipped",
                        knownNanos / 1_000_000, unknownNanos / 1_000_000)
                .isLessThan(5.0);
    }

    private long timeFailedLogin(String email) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long started = System.nanoTime();
            try {
                auth.login(email, "definitely not the password", "probe", "web");
            } catch (RuntimeException ignored) {
                // expected
            }
            best = Math.min(best, System.nanoTime() - started);
        }
        return best;
    }
}

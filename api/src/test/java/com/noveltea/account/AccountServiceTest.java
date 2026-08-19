package com.noveltea.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.account.AccountExceptions.NoDeletionPending;
import com.noveltea.auth.AuthExceptions.InvalidCredentials;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.binder.BinderService;
import com.noveltea.retention.RetentionService;
import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AccountServiceTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String NEW_PASSWORD = "an entirely different passphrase";

    @Autowired AccountService account;
    @Autowired AuthService auth;
    @Autowired BinderService binder;
    @Autowired RetentionService retention;
    @Autowired RecordingDelivery delivery;

    /** Captures the token instead of mailing it, so a test can complete a real reset. */
    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        RecordingDelivery recordingDelivery() {
            return new RecordingDelivery();
        }
    }

    static class RecordingDelivery implements PasswordResetDelivery {
        private final java.util.Map<String, String> sent = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void deliver(String email, String token) {
            sent.put(email, token);
        }

        String tokenFor(String email) {
            return sent.get(email);
        }

        boolean anythingSentTo(String email) {
            return sent.containsKey(email);
        }
    }

    private record Registered(String email, Session session) {}

    private Registered register() {
        String email = "acct-" + UUID.randomUUID() + "@example.com";
        return new Registered(email, auth.register(email, PASSWORD, "Laptop", "web"));
    }

    private long liveDevices(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM device WHERE user_id = :id AND revoked_at IS NULL")
                .param("id", userId).query(Long.class).single();
    }

    // -------------------------------------------------------- password reset

    @Test
    @DisplayName("a reset link changes the password and the old one stops working")
    void resetChangesPassword() {
        Registered me = register();
        account.requestReset(me.email(), "127.0.0.1");

        account.confirmReset(delivery.tokenFor(me.email()), NEW_PASSWORD);

        assertThatThrownBy(() -> auth.login(me.email(), PASSWORD, "d", "web"))
                .isInstanceOf(InvalidCredentials.class);
        assertThat(auth.login(me.email(), NEW_PASSWORD, "d", "web").userId())
                .isEqualTo(me.session().userId());
    }

    @Test
    @DisplayName("A RESET SIGNS EVERY DEVICE OUT")
    void resetRevokesAllDevices() {
        Registered me = register();
        String code = auth.createPairingCode(me.session().userId(), me.session().deviceId());
        Session phone = auth.pair(code, "Phone", "ios");
        assertThat(liveDevices(me.session().userId())).isEqualTo(2);

        account.requestReset(me.email(), "127.0.0.1");
        int signedOut = account.confirmReset(delivery.tokenFor(me.email()), NEW_PASSWORD);

        assertThat(signedOut).isEqualTo(2);
        assertThat(liveDevices(me.session().userId()))
                .as("someone resetting a password believes it was stolen; leaving the "
                        + "attacker's device paired would make the reset theatre")
                .isZero();
        assertThatThrownBy(() -> auth.refresh(phone.refreshToken()))
                .isInstanceOf(InvalidCredentials.class);
    }

    @Test
    @DisplayName("a reset token works exactly once")
    void resetTokenIsSingleUse() {
        Registered me = register();
        account.requestReset(me.email(), null);
        String token = delivery.tokenFor(me.email());

        account.confirmReset(token, NEW_PASSWORD);

        assertThatThrownBy(() -> account.confirmReset(token, "yet another passphrase here"))
                .isInstanceOf(InvalidCredentials.class);
    }

    @Test
    @DisplayName("requesting a second reset invalidates the first link")
    void newRequestInvalidatesTheOldLink() {
        Registered me = register();
        account.requestReset(me.email(), null);
        String first = delivery.tokenFor(me.email());
        account.requestReset(me.email(), null);
        String second = delivery.tokenFor(me.email());

        assertThat(second).isNotEqualTo(first);
        assertThatThrownBy(() -> account.confirmReset(first, NEW_PASSWORD))
                .as("two live links double the window in which one can be stolen")
                .isInstanceOf(InvalidCredentials.class);
        assertThat(account.confirmReset(second, NEW_PASSWORD)).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("an expired link is refused")
    void expiredLinkRefused() {
        Registered me = register();
        account.requestReset(me.email(), null);
        String token = delivery.tokenFor(me.email());
        jdbc.sql("UPDATE password_reset SET expires_at = now() - interval '1 hour'").update();

        assertThatThrownBy(() -> account.confirmReset(token, NEW_PASSWORD))
                .isInstanceOf(InvalidCredentials.class);
    }

    @Test
    @DisplayName("an unknown address produces no token and no hint")
    void unknownAddressRevealsNothing() {
        String stranger = "not-registered-" + UUID.randomUUID() + "@example.com";

        account.requestReset(stranger, null);

        assertThat(delivery.anythingSentTo(stranger)).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM password_reset").query(Long.class).single())
                .as("a row for an unknown address would leak through timing or size")
                .isZero();
    }

    @Test
    @DisplayName("reset tokens are stored only as hashes")
    void resetTokensAreHashed() {
        Registered me = register();
        account.requestReset(me.email(), null);
        String token = delivery.tokenFor(me.email());

        assertThat(jdbc.sql("SELECT token_hash FROM password_reset").query(String.class).single())
                .isNotEqualTo(token);
    }

    @Test
    @DisplayName("a short new password is refused")
    void shortPasswordRefused() {
        Registered me = register();
        account.requestReset(me.email(), null);
        assertThatThrownBy(() -> account.confirmReset(delivery.tokenFor(me.email()), "short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------ account deletion

    @Test
    @DisplayName("deletion is scheduled, not immediate, and nothing is destroyed yet")
    void deletionIsScheduled() {
        Registered me = register();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Novel')")
                .param("id", projectId).param("o", me.session().userId()).update();

        var status = account.requestDeletion(me.session().userId(), PASSWORD);

        assertThat(status.pending()).isTrue();
        assertThat(status.scheduledFor()).isAfter(status.requestedAt());
        assertThat(jdbc.sql("SELECT count(*) FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single())
                .as("this is the action taken in a bad moment; nothing may go yet")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("deletion requires the password, even though the caller is signed in")
    void deletionRequiresPassword() {
        Registered me = register();

        assertThatThrownBy(() -> account.requestDeletion(me.session().userId(), "wrong password here"))
                .isInstanceOf(InvalidCredentials.class);
        assertThatThrownBy(() -> account.requestDeletion(me.session().userId(), null))
                .isInstanceOf(InvalidCredentials.class);
        assertThat(account.status(me.session().userId()).pending())
                .as("a borrowed unlocked laptop must not destroy someone's novels")
                .isFalse();
    }

    @Test
    @DisplayName("a scheduled deletion can be cancelled")
    void deletionCanBeCancelled() {
        Registered me = register();
        account.requestDeletion(me.session().userId(), PASSWORD);

        account.cancelDeletion(me.session().userId());

        assertThat(account.status(me.session().userId()).pending()).isFalse();
        assertThatThrownBy(() -> account.cancelDeletion(me.session().userId()))
                .isInstanceOf(NoDeletionPending.class);
    }

    @Test
    @DisplayName("an account pending deletion can still sign in, or it could never cancel")
    void pendingAccountsCanStillSignIn() {
        Registered me = register();
        account.requestDeletion(me.session().userId(), PASSWORD);

        assertThat(auth.login(me.email(), PASSWORD, "d", "web").userId())
                .isEqualTo(me.session().userId());
    }

    @Test
    @DisplayName("nothing is purged before the grace period elapses")
    void gracePeriodIsRespected() {
        Registered me = register();
        account.requestDeletion(me.session().userId(), PASSWORD);

        assertThat(retention.sweep().deletedAccounts()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id")
                .param("id", me.session().userId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("after the grace period the account and everything in it is destroyed")
    void purgeCascadesAfterGrace() {
        Registered me = register();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Novel')")
                .param("id", projectId).param("o", me.session().userId()).update();
        UUID item = binder.create(projectId, me.session().deviceId(), null, "document", "Ch", null);

        account.requestDeletion(me.session().userId(), PASSWORD);
        jdbc.sql("UPDATE app_user SET deletion_requested_at = now() - interval '400 days' WHERE id = :id")
                .param("id", me.session().userId()).update();

        assertThat(account.purgeDueDeletions()).isEqualTo(1);

        for (var check : List.of(
                "SELECT count(*) FROM app_user WHERE id = '" + me.session().userId() + "'",
                "SELECT count(*) FROM project WHERE id = '" + projectId + "'",
                "SELECT count(*) FROM binder_item WHERE id = '" + item + "'")) {
            assertThat(jdbc.sql(check).query(Long.class).single())
                    .as("deleting an account must leave nothing behind: %s", check)
                    .isZero();
        }
    }

    @Test
    @DisplayName("a cancelled deletion is never carried out")
    void cancelledDeletionsAreNotPurged() {
        Registered me = register();
        account.requestDeletion(me.session().userId(), PASSWORD);
        jdbc.sql("UPDATE app_user SET deletion_requested_at = now() - interval '400 days' WHERE id = :id")
                .param("id", me.session().userId()).update();
        account.cancelDeletion(me.session().userId());

        assertThat(account.purgeDueDeletions()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id")
                .param("id", me.session().userId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("an account never scheduled for deletion is untouched by the sweep")
    void untouchedAccountsSurvive() {
        Registered me = register();
        assertThat(account.purgeDueDeletions()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id")
                .param("id", me.session().userId()).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a deleted account cannot sign in or reset its password")
    void deletedAccountsAreInert() {
        Registered me = register();
        jdbc.sql("UPDATE app_user SET deleted_at = now() WHERE id = :id")
                .param("id", me.session().userId()).update();

        assertThatThrownBy(() -> auth.login(me.email(), PASSWORD, "d", "web"))
                .isInstanceOf(InvalidCredentials.class);

        account.requestReset(me.email(), null);
        assertThat(delivery.anythingSentTo(me.email()))
                .as("a deleted account must not be recoverable through a reset link")
                .isFalse();
    }
}

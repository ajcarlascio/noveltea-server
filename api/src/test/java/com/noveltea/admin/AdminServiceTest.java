package com.noveltea.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.admin.AdminService.NewAccount;
import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthExceptions.EmailAlreadyRegistered;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.auth.Passwords;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** What an administrator can do to accounts, and what being one still does not buy. */
class AdminServiceTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired AdminService admin;
    @Autowired AuthService auth;

    private Session register(String label) {
        return auth.register(label + "-" + UUID.randomUUID() + "@example.com", PASSWORD, "Laptop", "web");
    }

    private UUID anAdministrator() {
        Session session = register("admin");
        jdbc.sql("UPDATE app_user SET is_admin = true WHERE id = :id")
                .param("id", session.userId()).update();
        return session.userId();
    }

    private boolean mustChange(UUID userId) {
        return Boolean.TRUE.equals(jdbc
                .sql("SELECT must_change_password FROM app_user WHERE id = :id")
                .param("id", userId).query(Boolean.class).single());
    }

    // ------------------------------------------------------------ who may act

    @Test
    @DisplayName("an ordinary account cannot list or create, and is told the route does not exist")
    void anOrdinaryAccountIsRefused() {
        UUID stranger = register("nobody").userId();

        // AccessDenied is rendered as 404 by the global handler: a 403 would confirm to
        // any signed-in account that an administration API is there to be attacked.
        assertThatThrownBy(() -> admin.listUsers(stranger)).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> admin.createUser(stranger, "new@example.com", null, null, false))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> admin.resetPassword(stranger, stranger, null))
                .isInstanceOf(AccessDenied.class);
    }

    @Test
    @DisplayName("a refused create writes nothing")
    void aRefusedCreateLeavesNoAccount() {
        UUID stranger = register("nobody").userId();

        assertThatThrownBy(() -> admin.createUser(stranger, "sneaked-in@example.com", null, null, true))
                .isInstanceOf(AccessDenied.class);

        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email = 'sneaked-in@example.com'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("administering the instance grants no access to anybody's writing")
    void anAdministratorIsNotAReader() {
        UUID administrator = anAdministrator();
        Session author = register("author");
        UUID theirProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Their Novel')")
                .param("id", theirProject).param("o", author.userId()).update();

        // The one surface an admin has over other accounts, rendered in full.
        String everything = admin.listUsers(administrator).toString();

        assertThat(everything)
                .as("an operator runs a server; they are not a reader of the novels on it")
                .doesNotContain("Their Novel")
                .doesNotContain(theirProject.toString());
    }

    // ------------------------------------------------------------- creating

    @Test
    @DisplayName("a created account gets a password it is immediately required to replace")
    void createdAccountsMustChooseTheirOwnPassword() {
        UUID administrator = anAdministrator();

        NewAccount created = admin.createUser(
                administrator, "newcomer@example.com", null, "A Newcomer", false);

        assertThat(created.password())
                .as("returned once, because only its hash is kept")
                .isNotBlank();
        assertThat(Passwords.isStrongEnough(created.password())).isTrue();
        assertThat(mustChange(created.id()))
                .as("the admin knows this password, so it is not yet the holder's account")
                .isTrue();

        // And it is a password that actually works.
        Session session = auth.login("newcomer@example.com", created.password(), "Their laptop", "web");
        assertThat(session.mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("a supplied password is used, and still has to clear the minimum")
    void aSuppliedPasswordIsUsedButNotExcused() {
        UUID administrator = anAdministrator();

        NewAccount created = admin.createUser(
                administrator, "chosen@example.com", "a perfectly good passphrase", null, false);
        assertThat(created.password()).isEqualTo("a perfectly good passphrase");
        assertThat(auth.login("chosen@example.com", "a perfectly good passphrase", "L", "web"))
                .isNotNull();

        assertThatThrownBy(() -> admin.createUser(administrator, "weak@example.com", "hunter2", null, false))
                .as("'changeme' travelling over chat is worse than a generated string")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least " + Passwords.MINIMUM_LENGTH);
    }

    @Test
    @DisplayName("an address that already has an account is reported, not silently reused")
    void duplicateAddressesAreRefused() {
        UUID administrator = anAdministrator();
        admin.createUser(administrator, "taken@example.com", null, null, false);

        assertThatThrownBy(() -> admin.createUser(administrator, "taken@example.com", null, null, false))
                .isInstanceOf(EmailAlreadyRegistered.class);
    }

    @Test
    @DisplayName("an administrator can make another administrator")
    void adminsCanBeCreated() {
        UUID administrator = anAdministrator();

        NewAccount second = admin.createUser(administrator, "second@example.com", null, null, true);

        // Proven by what it can do, not by reading back the column that set it.
        assertThat(admin.listUsers(second.id())).isNotEmpty();
    }

    @Test
    @DisplayName("the session says whether the account administers the instance")
    void theSessionCarriesTheAdminFlag() {
        UUID administrator = anAdministrator();
        NewAccount ordinary = admin.createUser(administrator, "plain@example.com", null, null, false);
        NewAccount second = admin.createUser(administrator, "boss@example.com", null, null, true);

        // So the client can decide whether to offer an administration screen without
        // probing a route it is probably not allowed to call.
        assertThat(auth.login("plain@example.com", ordinary.password(), "L", "web").isAdmin())
                .isFalse();
        assertThat(auth.login("boss@example.com", second.password(), "L", "web").isAdmin())
                .isTrue();
    }

    @Test
    @DisplayName("the flag is read fresh, not carried in a token that outlives the change")
    void revokingAdminTakesEffectImmediately() {
        UUID administrator = anAdministrator();
        NewAccount second = admin.createUser(administrator, "temp@example.com", null, null, true);
        Session theirs = auth.login("temp@example.com", second.password(), "L", "web");
        assertThat(theirs.isAdmin()).isTrue();

        jdbc.sql("UPDATE app_user SET is_admin = false WHERE id = :id")
                .param("id", second.id()).update();

        // The token they are still holding has not expired, and it does not matter: the
        // authorization check asks the database, not the claim.
        assertThatThrownBy(() -> admin.listUsers(second.id())).isInstanceOf(AccessDenied.class);
    }

    // ------------------------------------------------- setting a password

    @Test
    @DisplayName("setting a password signs every one of that account's devices out")
    void resettingAPasswordEndsEverySession() {
        UUID administrator = anAdministrator();
        Session locked = register("forgetful");
        Session theirSecondDevice = auth.login(
                emailOf(locked.userId()), PASSWORD, "Their phone", "ios");

        NewAccount reset = admin.resetPassword(administrator, locked.userId(), null);

        assertThat(jdbc.sql("SELECT count(*) FROM device WHERE user_id = :u AND revoked_at IS NULL")
                .param("u", locked.userId()).query(Long.class).single())
                .as("the account is being handed over; what was signed in was somebody else")
                .isZero();
        // Both refresh tokens are dead, so neither device can quietly outlive the change.
        assertThatThrownBy(() -> auth.refresh(locked.refreshToken())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> auth.refresh(theirSecondDevice.refreshToken()))
                .isInstanceOf(RuntimeException.class);

        assertThat(mustChange(locked.userId())).isTrue();
        assertThat(auth.login(reset.email(), reset.password(), "Laptop", "web").mustChangePassword())
                .isTrue();
    }

    @Test
    @DisplayName("an account that does not exist looks the same as one that does not belong here")
    void resettingAnUnknownAccountLooksLikeAnyOtherAbsence() {
        UUID administrator = anAdministrator();

        assertThatThrownBy(() -> admin.resetPassword(administrator, UUID.randomUUID(), null))
                .isInstanceOf(AccessDenied.class);
    }

    private String emailOf(UUID userId) {
        return jdbc.sql("SELECT email::text FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }
}

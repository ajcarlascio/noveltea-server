package com.noveltea.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.noveltea.admin.AdminBootstrap.Result;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * First run, and every run after it.
 *
 * <p>Driven directly rather than by restarting a Spring context, so each test can say what
 * the database looked like beforehand — which is the only interesting variable here. The
 * bean is off in the test profile precisely so it does not fire behind these tests' backs;
 * this class turns it on.
 *
 * <p>Note what the inherited fixture leaves behind: one ordinary account, seeded by
 * {@link AbstractPostgresTest}, which is not an administrator. That is not noise, it is the
 * realistic starting point — an instance with people on it and nobody in charge.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "noveltea.admin.enabled=true")
class AdminBootstrapTest extends AbstractPostgresTest {

    @Autowired AdminBootstrap bootstrap;
    @Autowired PasswordEncoder passwords;
    @Autowired AuthService auth;
    @Autowired AdminProperties properties;

    private record Admin(UUID id, String email, boolean mustChange, String hash) {}

    private Admin admin() {
        return jdbc.sql("""
                SELECT id, email::text AS email, must_change_password, password_hash
                  FROM app_user WHERE is_admin AND deleted_at IS NULL
                """)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .map(r -> new Admin(
                        (UUID) r.get("id"),
                        (String) r.get("email"),
                        Boolean.TRUE.equals(r.get("must_change_password")),
                        (String) r.get("password_hash")))
                .orElse(null);
    }

    private long accounts() {
        return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
    }

    @Test
    @DisplayName("an instance with no administrator gets one, and cannot keep its password")
    void firstRunCreatesTheAdministrator() {
        assertThat(bootstrap.bootstrap().result()).isEqualTo(Result.CREATED);

        Admin admin = admin();
        assertThat(admin).isNotNull();
        assertThat(admin.email()).isEqualTo(AdminProperties.DEFAULT_EMAIL);
        // The account is genuinely usable with the documented password...
        assertThat(passwords.matches(AdminProperties.DEFAULT_PASSWORD, admin.hash()))
                .as("the password in the README has to be the one that actually works")
                .isTrue();
        // ...and genuinely unable to do anything with it except replace it.
        assertThat(admin.mustChange()).isTrue();
    }

    @Test
    @DisplayName("the seeded credentials sign in, and the session says it is going nowhere")
    void seededCredentialsProduceALockedSession() {
        bootstrap.bootstrap();

        Session session = auth.login(
                AdminProperties.DEFAULT_EMAIL, AdminProperties.DEFAULT_PASSWORD, "Laptop", "web");

        assertThat(session.accessToken()).isNotBlank();
        assertThat(session.mustChangePassword())
                .as("a first-run account whose session is unrestricted is a default credential")
                .isTrue();
    }

    @Test
    @DisplayName("a second run changes nothing — a restart must not undo a chosen password")
    void restartIsANoOp() {
        bootstrap.bootstrap();
        // Stand in for the operator having since done the forced change.
        String chosen = passwords.encode("a password the operator actually chose");
        jdbc.sql("UPDATE app_user SET password_hash = :h, must_change_password = false WHERE is_admin")
                .param("h", chosen).update();
        long before = accounts();

        assertThat(bootstrap.bootstrap().result()).isEqualTo(Result.ALREADY_PRESENT);

        assertThat(admin().hash()).isEqualTo(chosen);
        assertThat(admin().mustChange())
                .as("a container restarting in a crash loop would otherwise re-lock the account")
                .isFalse();
        assertThat(accounts()).as("nothing new was created").isEqualTo(before);
    }

    @Test
    @DisplayName("an upgraded instance promotes the configured address, not whoever signed up first")
    void upgradePromotesTheConfiguredAccount() {
        // The fixture's own account is the one that signed up first, back when the column
        // did not exist. The configured address arrives later.
        UUID configured = UUID.randomUUID();
        String kept = passwords.encode("the operator's own password");
        jdbc.sql("INSERT INTO app_user (id, email, password_hash) VALUES (:id, :email, :hash)")
                .param("id", configured).param("email", properties.email()).param("hash", kept).update();
        long before = accounts();

        assertThat(bootstrap.bootstrap().result()).isEqualTo(Result.PROMOTED);

        Admin admin = admin();
        assertThat(admin.id())
                .as("promoting the oldest account would hand the server to a stranger")
                .isEqualTo(configured);
        assertThat(admin.hash())
                .as("promotion must never rewrite a password its owner already chose")
                .isEqualTo(kept);
        assertThat(admin.mustChange())
                .as("nobody but the holder knows this password, so there is nothing to force")
                .isFalse();
        assertThat(accounts()).isEqualTo(before);
    }

    @Test
    @DisplayName("a configured password strong enough to keep is not forced to change")
    void aRealConfiguredPasswordIsLeftAlone() {
        AdminBootstrap withRealPassword = new AdminBootstrap(
                jdbc, passwords,
                new AdminProperties(true, "operator@example.com", "a genuinely long passphrase"));

        assertThat(withRealPassword.bootstrap().result()).isEqualTo(Result.CREATED);

        Admin admin = admin();
        assertThat(admin.email()).isEqualTo("operator@example.com");
        assertThat(admin.mustChange())
                .as("an operator who chose a real password should not be made to choose again")
                .isFalse();
    }

    @Test
    @DisplayName("a guest address is refused rather than failing the startup it runs inside")
    void aGuestCannotBecomeAnAdministrator() {
        jdbc.sql("INSERT INTO app_user (id, email, is_guest) VALUES (:id, :email, true)")
                .param("id", UUID.randomUUID()).param("email", properties.email()).update();

        // The CHECK constraint would refuse the UPDATE; the point is that this is reported
        // rather than thrown out of an ApplicationRunner during boot.
        assertThat(bootstrap.bootstrap().result()).isEqualTo(Result.REFUSED_GUEST);
        assertThat(admin()).isNull();
    }
}

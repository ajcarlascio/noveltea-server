package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.auth.PasswordChangeRequiredFilter;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The lock on an account whose password somebody else chose.
 *
 * <p>Deliberately at the HTTP boundary rather than against the service. "The app shows a
 * change-password screen" is not a control — the token is real and anything holding it can
 * call the API directly — so the only assertion worth making is that the API itself refuses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ForcedPasswordChangeTest extends AbstractPostgresTest {

    private static final String SEEDED = "the password an admin chose";
    private static final String CHOSEN = "a passphrase nobody else has seen";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;

    private String email;

    /** An account in exactly the state a first-run administrator is in. */
    private Session locked() {
        email = "locked-" + UUID.randomUUID() + "@example.com";
        Session initial = auth.register(email, SEEDED, "Laptop", "web");
        jdbc.sql("UPDATE app_user SET must_change_password = true WHERE id = :id")
                .param("id", initial.userId()).update();
        // Signed in again, because the flag is read when the token is minted.
        return auth.login(email, SEEDED, "Laptop", "web");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String change(String current, String next) {
        return "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next);
    }

    @Test
    @DisplayName("the token works, and works for nothing but choosing a password")
    void everyOtherRouteIsRefused() throws Exception {
        Session session = locked();

        for (var request : java.util.List.of(
                get("/api/v1/projects"),
                get("/api/v1/devices"),
                get("/api/v1/account/deletion"),
                post("/api/v1/auth/pairing-codes"))) {

            MvcResult result = mvc.perform(request
                            .header("Authorization", "Bearer " + session.accessToken())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();

            // 403, not 401: the credentials are perfectly valid and presenting them again
            // would change nothing, which is the distinction clients branch on.
            assertThat(result.getResponse().getStatus())
                    .as("%s must be refused while the password is not the holder's",
                            result.getRequest().getRequestURI())
                    .isEqualTo(403);
            assertThat(body(result).get("error").asText())
                    .isEqualTo(PasswordChangeRequiredFilter.ERROR_CODE);
        }
    }

    @Test
    @DisplayName("scheduling the destruction of an instance's projects is not an escape hatch")
    void theNeighbouringAccountRouteIsAlsoRefused() throws Exception {
        Session session = locked();

        // /account/deletion sits one path segment from /account/password. A prefix rule
        // would let a locked account queue the deletion of everything on the server.
        MvcResult result = mvc.perform(post("/api/v1/account/deletion")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"%s\"}".formatted(SEEDED)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT deletion_requested_at FROM app_user WHERE id = :id")
                .param("id", session.userId()).query(java.time.OffsetDateTime.class).optional())
                .isEmpty();
    }

    @Test
    @DisplayName("changing the password opens everything, using the token it hands back")
    void changingThePasswordLiftsTheLock() throws Exception {
        Session session = locked();

        MvcResult changed = mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(change(SEEDED, CHOSEN)))
                .andReturn();

        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        JsonNode json = body(changed);
        assertThat(json.get("mustChangePassword").asBoolean()).isFalse();
        String freshToken = json.get("accessToken").asText();
        assertThat(freshToken).isNotBlank().isNotEqualTo(session.accessToken());

        // The replacement token is the one that matters: the old one was minted before the
        // change and still carries the claim, so a client that kept it would appear to have
        // changed its password and then been locked out anyway.
        assertThat(mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer " + freshToken))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);

        // And the new password is the one that signs in from now on.
        assertThat(auth.login(email, CHOSEN, "Laptop", "web").mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("the current password still has to be right, and the new one has to be new")
    void theEscapeHatchIsNotAFreePass() throws Exception {
        Session session = locked();

        MvcResult wrong = mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(change("not the seeded password", CHOSEN)))
                .andReturn();
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);

        MvcResult unchanged = mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(change(SEEDED, SEEDED)))
                .andReturn();
        assertThat(unchanged.getResponse().getStatus())
                .as("keeping the password an admin chose would satisfy the rule without meeting it")
                .isEqualTo(400);

        MvcResult tooShort = mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(change(SEEDED, "hunter2")))
                .andReturn();
        assertThat(tooShort.getResponse().getStatus()).isEqualTo(400);

        // None of that let the account out.
        assertThat(mvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + session.accessToken()))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("an ordinary account is untouched by any of this")
    void anOrdinaryAccountIsNotAffected() throws Exception {
        Session ordinary = auth.register(
                "ordinary-" + UUID.randomUUID() + "@example.com", SEEDED, "Laptop", "web");

        assertThat(ordinary.mustChangePassword()).isFalse();
        assertThat(mvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + ordinary.accessToken()))
                        .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("changing a password signs out the other devices but not the one that did it")
    void otherDevicesAreSignedOut() throws Exception {
        Session session = locked();
        Session elsewhere = auth.login(email, SEEDED, "A phone left somewhere", "ios");
        // Counted rather than assumed: this account also has the device it first
        // registered on, and a literal here would be arithmetic about the fixture rather
        // than a claim about the code.
        long othersBefore = jdbc.sql("""
                SELECT count(*) FROM device
                 WHERE user_id = :u AND id <> :self AND revoked_at IS NULL
                """)
                .param("u", session.userId()).param("self", session.deviceId())
                .query(Long.class).single();
        assertThat(othersBefore).isGreaterThan(1);

        MvcResult changed = mvc.perform(post("/api/v1/account/password")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(change(SEEDED, CHOSEN)))
                .andReturn();

        assertThat(body(changed).get("devicesSignedOut").asInt()).isEqualTo((int) othersBefore);
        assertThat(jdbc.sql("SELECT revoked_at FROM device WHERE id = :id")
                        .param("id", elsewhere.deviceId())
                        .query(java.time.OffsetDateTime.class).optional())
                .as("the phone that was signed in under the old password is out")
                .isPresent();
        assertThat(jdbc.sql("SELECT revoked_at FROM device WHERE id = :id")
                        .param("id", session.deviceId())
                        .query(java.time.OffsetDateTime.class).optional())
                .as("throwing somebody out of the device they just used is a punishment for good hygiene")
                .isEmpty();
    }
}

package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.admin.AdminService;
import com.noveltea.auth.AuthExceptions.RegistrationClosed;
import com.noveltea.auth.AuthService;
import com.noveltea.support.AbstractPostgresTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who may make an account on somebody else's server.
 *
 * <p>The suite as a whole runs with registration open, because almost every test needs an
 * account and reaches for {@code auth.register} to get one. This class turns it off, which
 * is the shipped default, and pins what that costs and what it does not.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "noveltea.auth.open-registration=false")
@AutoConfigureMockMvc
class RegistrationPolicyTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired AdminService admin;
    @Autowired ObjectMapper mapper;

    private MvcResult attemptToRegister(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","deviceName":"Laptop","platform":"web"}
                                """.formatted(email, PASSWORD)))
                .andReturn();
    }

    @Test
    @DisplayName("a stranger who can reach the address still cannot make themselves an account")
    void registrationIsClosed() throws Exception {
        String email = "walkup-" + UUID.randomUUID() + "@example.com";

        MvcResult result = attemptToRegister(email);

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        JsonNode error = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(error.get("error").asText()).isEqualTo("registration_closed");
        // Says what to do instead. "Invalid credentials" would send somebody hunting for a
        // typo in an address that is perfectly correct.
        assertThat(error.get("message").asText()).contains("administrator");

        assertThat(jdbc.sql("SELECT count(*) FROM app_user WHERE email = :email")
                .param("email", email).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("the refusal is the same for an address that exists as for one that does not")
    void theRefusalRevealsNothing() throws Exception {
        UUID administrator = anAdministrator();
        String taken = "taken-" + UUID.randomUUID() + "@example.com";
        admin.createUser(administrator, taken, null, null, false);

        MvcResult existing = attemptToRegister(taken);
        MvcResult absent = attemptToRegister("absent-" + UUID.randomUUID() + "@example.com");

        assertThat(existing.getResponse().getStatus()).isEqualTo(absent.getResponse().getStatus());
        assertThat(mapper.readTree(existing.getResponse().getContentAsString()).get("error"))
                .as("a different answer for a known address is an account-enumeration oracle")
                .isEqualTo(mapper.readTree(absent.getResponse().getContentAsString()).get("error"));
    }

    @Test
    @DisplayName("the service refuses too, not only the route")
    void theRuleIsNotOnlyInTheController() {
        assertThatThrownBy(() -> auth.register("direct@example.com", PASSWORD, "Laptop", "web"))
                .isInstanceOf(RegistrationClosed.class);
    }

    @Test
    @DisplayName("an administrator makes the accounts instead, and the closed door does not stop them")
    void administratorsStillCreateAccounts() {
        UUID administrator = anAdministrator();

        var created = admin.createUser(administrator, "invited@example.com", null, "Invited", false);

        assertThat(auth.login("invited@example.com", created.password(), "Laptop", "web"))
                .as("an instance with registration closed and no way in is an instance nobody can use")
                .isNotNull();
    }

    @Test
    @DisplayName("the shipped configuration is the closed one")
    void closedIsWhatAnOperatorGetsWithoutConfiguringAnything() throws Exception {
        // This class proves the behaviour with the property forced. What it cannot prove
        // from inside a context that sets it is the value an operator gets by setting
        // nothing at all — and that value is the whole decision. So read it from the file
        // that ships.
        @SuppressWarnings("unchecked")
        Map<String, Object> yaml = new org.yaml.snakeyaml.Yaml()
                .load(new ClassPathResource("application.yml").getInputStream());
        @SuppressWarnings("unchecked")
        Map<String, Object> auth =
                (Map<String, Object>) ((Map<String, Object>) yaml.get("noveltea")).get("auth");

        assertThat(auth.get("open-registration"))
                .as("an operator who configures nothing must get a server that does not "
                        + "accept walk-up signups")
                .isEqualTo("${NOVELTEA_OPEN_REGISTRATION:false}");
    }

    private UUID anAdministrator() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO app_user (id, email, password_hash, is_admin)
                VALUES (:id, :email, 'x', true)
                """)
                .param("id", id).param("email", "root-" + id + "@example.com").update();
        return id;
    }
}

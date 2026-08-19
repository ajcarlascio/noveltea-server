package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
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

/** HTTP-level behaviour: the security boundary and the single error shape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class WebApiTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;

    private Session register() {
        return auth.register("user-" + UUID.randomUUID() + "@example.com", PASSWORD, "Laptop", "web");
    }

    private JsonNode body(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("an unauthenticated call is refused")
    void unauthenticatedIsRefused() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/sync", projectId))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("a garbage bearer token does not authenticate")
    void garbageTokenIsRefused() throws Exception {
        mvc.perform(get("/api/v1/projects/{id}/sync", projectId)
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));
    }

    @Test
    @DisplayName("registering is open and returns a usable session")
    void registrationIsOpen() throws Exception {
        String payload = """
                {"email":"open-%s@example.com","password":"%s","deviceName":"Laptop","platform":"web"}
                """.formatted(UUID.randomUUID(), PASSWORD);

        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode json = body(result);
        assertThat(json.get("accessToken").asText()).isNotBlank();
        assertThat(json.get("refreshToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("another account's project reports 404, never 403")
    void foreignProjectLooksAbsent() throws Exception {
        Session owner = register();
        UUID owned = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Secret')")
                .param("id", owned).param("o", owner.userId()).update();

        Session stranger = register();

        MvcResult result = mvc.perform(get("/api/v1/projects/{id}/binder", owned)
                        .header("Authorization", "Bearer " + stranger.accessToken()))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("403 would confirm the project exists to someone never granted it")
                .isEqualTo(404);
        assertThat(body(result).get("message").asText())
                .as("the message must not describe what was found")
                .doesNotContain("Secret");
    }

    @Test
    @DisplayName("the owner can read their own project")
    void ownerCanRead() throws Exception {
        Session owner = register();
        UUID owned = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Mine')")
                .param("id", owned).param("o", owner.userId()).update();

        mvc.perform(get("/api/v1/projects/{id}/binder", owned)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(200));
    }

    @Test
    @DisplayName("errors share one shape: error, message, path, timestamp")
    void errorsShareOneShape() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-but-long-enough\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        JsonNode json = body(result);
        assertThat(json.get("error").asText()).isEqualTo("invalid_credentials");
        assertThat(json.hasNonNull("message")).isTrue();
        assertThat(json.get("path").asText()).isEqualTo("/api/v1/auth/login");
        assertThat(json.hasNonNull("timestamp")).isTrue();
    }

    @Test
    @DisplayName("malformed JSON is a 400 that reveals nothing internal")
    void malformedJsonIsBadRequest() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        JsonNode json = body(result);
        assertThat(json.get("error").asText()).isEqualTo("bad_request");
        String message = json.get("message").asText();
        assertThat(message)
                .as("framework text can carry class names, SQL and parameter values")
                .doesNotContain("com.fasterxml")
                .doesNotContain("Exception")
                .doesNotContain("jdbc");
    }

    @Test
    @DisplayName("a short password is rejected with a usable reason")
    void shortPasswordIsBadRequest() throws Exception {
        String payload = """
                {"email":"short-%s@example.com","password":"tiny","deviceName":"L","platform":"web"}
                """.formatted(UUID.randomUUID());

        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("message").asText()).contains("at least 12");
    }

    @Test
    @DisplayName("a malformed path id is a 400, not a 500")
    void malformedPathIdIsBadRequest() throws Exception {
        Session session = register();
        MvcResult result = mvc.perform(get("/api/v1/projects/{id}/binder", "not-a-uuid")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result).get("error").asText()).isEqualTo("bad_request");
    }

    @Test
    @DisplayName("pairing endpoints require a token to mint a code, but not to redeem one")
    void pairingCodeMintingRequiresAuth() throws Exception {
        mvc.perform(post("/api/v1/auth/pairing-codes"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(401));

        Session session = register();
        MvcResult minted = mvc.perform(post("/api/v1/auth/pairing-codes")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();
        assertThat(minted.getResponse().getStatus()).isEqualTo(201);
        String code = body(minted).get("code").asText();

        MvcResult paired = mvc.perform(post("/api/v1/auth/pair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"%s\",\"deviceName\":\"Phone\",\"platform\":\"ios\"}".formatted(code)))
                .andReturn();
        assertThat(paired.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(paired).get("userId").asText()).isEqualTo(session.userId().toString());
    }

    @Test
    @DisplayName("a missing required query parameter is a 400 that names it, not a 500")
    void missingQueryParameterIsBadRequest() throws Exception {
        Session session = register();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Mine')")
                .param("id", projectId).param("o", session.userId()).update();

        MvcResult result = mvc.perform(get("/api/v1/projects/{id}/search", projectId)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("a 500 tells a client to retry something that can never succeed")
                .isEqualTo(400);
        assertThat(body(result).get("message").asText()).contains("q");
    }

    @Test
    @DisplayName("a NUL byte in a JSON field is a 400, not a 500")
    void nulByteInBodyIsBadRequest() throws Exception {
        Session session = register();
        String payload = "{\"title\":\"A\\u0000B\"}";

        MvcResult result = mvc.perform(post("/api/v1/projects")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("Postgres text cannot hold U+0000; reaching the driver reports 500")
                .isEqualTo(400);
        assertThat(body(result).get("message").asText()).contains("null character");
    }

    @Test
    @DisplayName("a NUL byte in a query parameter is a 400, not a 500")
    void nulByteInQueryIsBadRequest() throws Exception {
        Session session = register();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Mine')")
                .param("id", projectId).param("o", session.userId()).update();

        MvcResult result = mvc.perform(get("/api/v1/projects/{id}/search", projectId)
                        .queryParam("q", "A\u0000B")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }
}

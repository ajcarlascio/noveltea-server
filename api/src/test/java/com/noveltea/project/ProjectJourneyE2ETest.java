package com.noveltea.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end journeys over the real HTTP API — no service is called directly.
 *
 * <p>Every step goes through a controller, the security filter chain, JSON serialisation
 * and the database, exactly as a client would. Assertions read the database independently
 * rather than trusting a response the same code produced, so a bug that corrupted writing
 * and reading symmetrically would still surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProjectJourneyE2ETest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    // ------------------------------------------------------------- plumbing

    private JsonNode send(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus())
                .as("unexpected status for %s — body: %s", result.getRequest().getRequestURI(), body)
                .isEqualTo(expectedStatus);
        return body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String token, String body) {
        builder.contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private JsonNode registerOverHttp() throws Exception {
        return send(json(post("/api/v1/auth/register"), null, """
                {"email":"e2e-%s@example.com","password":"%s","deviceName":"Laptop","platform":"web"}
                """.formatted(UUID.randomUUID(), PASSWORD)), 201);
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a writer signs up, creates a novel, writes a chapter, and a second device receives it")
    void fullJourneyFromSignupToSecondDevice() throws Exception {
        // 1. Sign up.
        JsonNode laptop = registerOverHttp();
        String laptopToken = laptop.get("accessToken").asText();
        UUID userId = UUID.fromString(laptop.get("userId").asText());

        // 2. Create a project.
        JsonNode project = send(json(post("/api/v1/projects"), laptopToken,
                "{\"title\":\"The Lighthouse\",\"settings\":{\"wordCountTarget\":90000}}"), 201);
        UUID projectId = UUID.fromString(project.get("id").asText());

        assertThat(jdbc.sql("SELECT owner_id FROM project WHERE id = :id")
                .param("id", projectId).query(UUID.class).single())
                .as("the creator must be recorded as owner in the database, not just the response")
                .isEqualTo(userId);

        // 3. Add a chapter to the binder.
        JsonNode item = send(json(post("/api/v1/projects/{p}/binder-items", projectId), laptopToken,
                "{\"type\":\"document\",\"title\":\"Chapter One\"}"), 201);
        UUID itemId = UUID.fromString(item.get("id").asText());

        // 4. Write its content through sync.
        JsonNode push = send(json(post("/api/v1/projects/{p}/sync", projectId), laptopToken, """
                {"changes":[{"entityType":"document","entityId":"%s","op":"create",
                 "data":{"content":%s,"word_count":6,"search_text":"the lamp had not been lit"}}]}
                """.formatted(itemId, doc("the lamp had not been lit"))), 200);

        assertThat(push.get("conflicts")).isEmpty();
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", itemId).query(String.class).single())
                .contains("the lamp had not been lit");

        // 5. Pair a phone.
        String code = send(json(post("/api/v1/auth/pairing-codes"), laptopToken, ""), 201)
                .get("code").asText();
        JsonNode phone = send(json(post("/api/v1/auth/pair"), null,
                "{\"code\":\"%s\",\"deviceName\":\"Phone\",\"platform\":\"ios\"}".formatted(code)), 200);
        String phoneToken = phone.get("accessToken").asText();

        // 6. The phone pulls and sees the chapter.
        JsonNode pull = send(get("/api/v1/projects/{p}/sync?since=0", projectId)
                .header("Authorization", "Bearer " + phoneToken), 200);

        assertThat(pull.get("changes")).isNotEmpty();
        boolean sawChapter = false;
        for (JsonNode change : pull.get("changes")) {
            if ("document".equals(change.get("entityType").asText())
                    && itemId.toString().equals(change.get("entityId").asText())) {
                assertThat(change.get("data").get("content").toString()).contains("the lamp had not been lit");
                sawChapter = true;
            }
        }
        assertThat(sawChapter).as("the second device must receive the chapter it never wrote").isTrue();

        // 7. The project lists with live counts.
        JsonNode listed = send(get("/api/v1/projects").header("Authorization", "Bearer " + phoneToken), 200);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("documentCount").asInt()).isEqualTo(1);
        assertThat(listed.get(0).get("settings").get("wordCountTarget").asInt()).isEqualTo(90000);
    }

    @Test
    @DisplayName("deleting is reversible over HTTP, and purging requires deleting first")
    void deleteRestorePurgeJourney() throws Exception {
        JsonNode session = registerOverHttp();
        String token = session.get("accessToken").asText();

        UUID projectId = UUID.fromString(send(json(post("/api/v1/projects"), token,
                "{\"title\":\"Discardable\"}"), 201).get("id").asText());

        // Purge is refused while the project is live.
        mvc.perform(delete("/api/v1/projects/{p}/purge", projectId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(409));
        assertThat(jdbc.sql("SELECT count(*) FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single()).isEqualTo(1);

        // Delete hides it.
        send(delete("/api/v1/projects/{p}", projectId).header("Authorization", "Bearer " + token), 204);
        assertThat(send(get("/api/v1/projects").header("Authorization", "Bearer " + token), 200)).isEmpty();
        assertThat(send(get("/api/v1/projects/deleted").header("Authorization", "Bearer " + token), 200))
                .hasSize(1);
        mvc.perform(get("/api/v1/projects/{p}", projectId).header("Authorization", "Bearer " + token))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(404));

        // Restore brings it back.
        send(json(post("/api/v1/projects/{p}/restore", projectId), token, ""), 200);
        assertThat(send(get("/api/v1/projects").header("Authorization", "Bearer " + token), 200)).hasSize(1);

        // Delete then purge really removes it.
        send(delete("/api/v1/projects/{p}", projectId).header("Authorization", "Bearer " + token), 204);
        send(delete("/api/v1/projects/{p}/purge", projectId).header("Authorization", "Bearer " + token), 204);
        assertThat(jdbc.sql("SELECT count(*) FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("two devices conflict, and the author resolves it through the merge endpoints")
    void conflictAndMergeJourney() throws Exception {
        JsonNode laptop = registerOverHttp();
        String laptopToken = laptop.get("accessToken").asText();

        UUID projectId = UUID.fromString(send(json(post("/api/v1/projects"), laptopToken,
                "{\"title\":\"Contested\"}"), 201).get("id").asText());
        UUID itemId = UUID.fromString(send(json(post("/api/v1/projects/{p}/binder-items", projectId),
                laptopToken, "{\"type\":\"document\",\"title\":\"Chapter One\"}"), 201).get("id").asText());

        send(json(post("/api/v1/projects/{p}/sync", projectId), laptopToken, """
                {"changes":[{"entityType":"document","entityId":"%s","op":"create","data":{"content":%s}}]}
                """.formatted(itemId, doc("original"))), 200);

        // A second device pairs, then both write from version 1.
        String code = send(json(post("/api/v1/auth/pairing-codes"), laptopToken, ""), 201).get("code").asText();
        String phoneToken = send(json(post("/api/v1/auth/pair"), null,
                "{\"code\":\"%s\",\"deviceName\":\"Phone\",\"platform\":\"ios\"}".formatted(code)), 200)
                .get("accessToken").asText();

        send(json(post("/api/v1/projects/{p}/sync", projectId), laptopToken, """
                {"changes":[{"entityType":"document","entityId":"%s","op":"update","baseVersion":1,
                 "data":{"content":%s}}]}
                """.formatted(itemId, doc("laptop version"))), 200);

        JsonNode losing = send(json(post("/api/v1/projects/{p}/sync", projectId), phoneToken, """
                {"changes":[{"entityType":"document","entityId":"%s","op":"update","baseVersion":1,
                 "data":{"content":%s}}]}
                """.formatted(itemId, doc("phone version"))), 200);

        assertThat(losing.get("conflicts")).hasSize(1);
        UUID copyId = UUID.fromString(losing.get("conflicts").get(0).get("conflictCopyId").asText());

        // Both versions must exist in the database before any merge happens.
        assertThat(jdbc.sql("SELECT string_agg(content::text, ' | ') FROM document")
                .query(String.class).single())
                .contains("laptop version").contains("phone version");

        // The conflict is listed, and detail returns both sides.
        JsonNode conflicts = send(get("/api/v1/projects/{p}/conflicts", projectId)
                .header("Authorization", "Bearer " + laptopToken), 200);
        assertThat(conflicts).hasSize(1);

        JsonNode detail = send(get("/api/v1/conflicts/{c}", copyId)
                .header("Authorization", "Bearer " + laptopToken), 200);
        assertThat(detail.get("originalContent").toString()).contains("laptop version");
        assertThat(detail.get("copyContent").toString()).contains("phone version");
        long originalVersion = detail.get("originalVersion").asLong();

        // Resolve with a merged text.
        send(json(post("/api/v1/conflicts/{c}/resolve", copyId), laptopToken,
                "{\"content\":%s,\"baseVersion\":%d}".formatted(doc("laptop and phone, reconciled"), originalVersion)),
                200);

        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", itemId).query(String.class).single()).contains("reconciled");
        assertThat(send(get("/api/v1/projects/{p}/conflicts", projectId)
                .header("Authorization", "Bearer " + laptopToken), 200)).isEmpty();

        // The rejected text survives in the trash rather than being destroyed.
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", copyId).query(String.class).single()).contains("phone version");
    }

    @Test
    @DisplayName("a stranger cannot reach any of another account's project endpoints")
    void strangerIsLockedOutOfEveryEndpoint() throws Exception {
        JsonNode owner = registerOverHttp();
        UUID projectId = UUID.fromString(send(json(post("/api/v1/projects"), owner.get("accessToken").asText(),
                "{\"title\":\"Private\"}"), 201).get("id").asText());

        String stranger = registerOverHttp().get("accessToken").asText();

        for (MockHttpServletRequestBuilder request : java.util.List.of(
                get("/api/v1/projects/{p}", projectId),
                get("/api/v1/projects/{p}/binder", projectId),
                get("/api/v1/projects/{p}/sync", projectId),
                get("/api/v1/projects/{p}/conflicts", projectId),
                delete("/api/v1/projects/{p}", projectId),
                delete("/api/v1/projects/{p}/purge", projectId))) {
            mvc.perform(request.header("Authorization", "Bearer " + stranger))
                    .andExpect(r -> assertThat(r.getResponse().getStatus())
                            .as("every route must hide the project, and none may confirm it exists")
                            .isEqualTo(404));
        }

        assertThat(jdbc.sql("SELECT count(*) FROM project WHERE id = :id AND deleted_at IS NULL")
                .param("id", projectId).query(Long.class).single())
                .as("no rejected request may have modified anything")
                .isEqualTo(1);
    }
}

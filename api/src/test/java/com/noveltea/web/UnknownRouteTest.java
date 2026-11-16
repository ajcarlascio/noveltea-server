package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** A route that does not exist is the caller's mistake: 404 or 405 in the standard body, never 500. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class UnknownRouteTest extends AbstractPostgresTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;

    private Session session() {
        return auth.register("u-" + UUID.randomUUID() + "@example.com", "correct horse battery staple", "L", "web");
    }

    @Test
    @DisplayName("an unknown route answers 404 not_found in the standard body")
    void unknownRouteIs404() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/does-not-exist")
                        .header("Authorization", "Bearer " + session().accessToken()))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("error").asText()).isEqualTo("not_found");
        assertThat(body.get("message").asText()).isEqualTo("not found");
        assertThat(body.get("path").asText()).isEqualTo("/api/v1/does-not-exist");
    }

    @Test
    @DisplayName("an unauthenticated unknown route is refused exactly as a known one is")
    void unauthenticatedUnknownRouteIs401() throws Exception {
        int known = mvc.perform(get("/api/v1/projects/{id}/sync", projectId)).andReturn().getResponse().getStatus();
        MvcResult unknown = mvc.perform(get("/api/v1/does-not-exist")).andReturn();

        assertThat(known).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("the wrong method on a known route answers 405, not 500")
    void wrongMethodIs405() throws Exception {
        MvcResult r = mvc.perform(delete("/api/v1/projects/{id}/sync", projectId)
                        .header("Authorization", "Bearer " + session().accessToken()))
                .andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(405);
        assertThat(mapper.readTree(r.getResponse().getContentAsString()).get("error").asText())
                .isEqualTo("method_not_allowed");
    }
}

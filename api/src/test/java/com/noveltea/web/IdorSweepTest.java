package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.binder.BinderService;
import com.noveltea.comment.CommentService;
import com.noveltea.snapshot.SnapshotService;
import com.noveltea.support.AbstractPostgresTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Sweeps every mapped route with a stranger's token.
 *
 * <p>Enumerated from the handler mapping rather than written by hand, so adding a
 * controller method that forgets its authorization check fails this test immediately —
 * which is the only way a check like that gets caught before a user finds it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class IdorSweepTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    /**
     * Public by design: obtaining a token, or recovering an account when you cannot.
     *
     * <p>Listed rather than pattern-matched, so adding a route under /auth does not become
     * public by accident — a new one fails this sweep until someone states the intent.
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/auth/refresh", "/api/v1/auth/pair",
            "/api/v1/auth/password-reset", "/api/v1/auth/password-reset/confirm");

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired BinderService binder;
    @Autowired SnapshotService snapshots;
    @Autowired CommentService comments;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerMapping handlerMapping;

    private Session register() {
        return auth.register("sweep-" + UUID.randomUUID() + "@example.com", PASSWORD, "Laptop", "web");
    }

    private record Route(HttpMethod method, String pattern) {}

    /** Every path variable the API uses. A new one must be added here, not skipped. */
    private static String fill(
            String pattern, UUID projectId, UUID itemId, UUID deviceId,
            UUID snapshotId, UUID commentId, UUID jobId, UUID userId) {
        return pattern
                .replace("{projectId}", projectId.toString())
                // The administration routes name an account rather than a resource inside
                // one. Filled with the victim's own id, so what the sweep asks is the
                // question that matters: can a stranger take over somebody else's account?
                .replace("{userId}", userId.toString())
                .replace("{itemId}", itemId.toString())
                .replace("{documentId}", itemId.toString())
                .replace("{copyId}", itemId.toString())
                .replace("{deviceId}", deviceId.toString())
                .replace("{snapshotId}", snapshotId.toString())
                .replace("{commentId}", commentId.toString())
                .replace("{jobId}", jobId.toString());
    }

    /** Every route the application actually exposes, minus the public auth endpoints. */
    private List<Route> mappedRoutes() {
        List<Route> routes = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) continue;
            for (var pattern : patterns.getPatterns()) {
                String path = pattern.getPatternString();
                if (!path.startsWith("/api/v1") || PUBLIC_PATHS.contains(path)) continue;
                var methods = info.getMethodsCondition().getMethods();
                if (methods.isEmpty()) continue;
                for (var method : methods) {
                    routes.add(new Route(HttpMethod.valueOf(method.name()), path));
                }
            }
        }
        return routes;
    }

    @Test
    @DisplayName("no route lets a stranger touch another account's data")
    void strangerCannotReachAnyRoute() throws Exception {
        // A victim with a real project, a real binder item and a real conflict copy.
        Session victim = register();
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Private Novel')")
                .param("id", projectId).param("o", victim.userId()).update();
        UUID itemId = binder.create(projectId, victim.deviceId(), null, "document", "Chapter One", null);
        jdbc.sql("INSERT INTO document (id, content) VALUES (:id, '{\"type\":\"doc\"}'::jsonb)")
                .param("id", itemId).update();

        UUID snapshotId = snapshots.capture(itemId, "their milestone", false, victim.deviceId());
        UUID commentId = comments.create(itemId, victim.userId(), victim.deviceId(),
                "their private note", null, null);
        UUID jobId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO compile_job (id, project_id, inline_config, format, destination, status)
                VALUES (:id, :p, '{}'::jsonb, 'md', 'download', 'queued')
                """).param("id", jobId).param("p", projectId).update();

        Session stranger = register();
        List<Route> routes = mappedRoutes();
        assertThat(routes).as("the sweep must actually find routes").isNotEmpty();

        List<String> leaks = new ArrayList<>();
        List<String> selfScoped = new ArrayList<>();
        List<String> unfillable = new ArrayList<>();

        for (Route route : routes) {
            boolean targetsVictim = route.pattern().contains("{");
            String path = fill(route.pattern(), projectId, itemId, victim.deviceId(),
                    snapshotId, commentId, jobId, victim.userId());
            if (path.contains("{")) {
                unfillable.add(route.method() + " " + route.pattern());
                continue;
            }

            MvcResult result = mvc.perform(MockMvcRequestBuilders.request(route.method(), path)
                            .header("Authorization", "Bearer " + stranger.accessToken())
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();
            int status = result.getResponse().getStatus();
            String body = result.getResponse().getContentAsString();

            if (targetsVictim) {
                // The path names a resource the victim owns: it must look absent.
                if (status >= 200 && status < 300) {
                    leaks.add(route.method() + " " + path + " -> " + status);
                }
            } else {
                // A collection scoped to the caller. 2xx is correct — what matters is that
                // it contains none of the victim's data.
                selfScoped.add(route.method() + " " + route.pattern());
                assertThat(body)
                        .as("%s %s leaked the victim's data into a caller-scoped response",
                                route.method(), route.pattern())
                        .doesNotContain(projectId.toString())
                        .doesNotContain(itemId.toString())
                        .doesNotContain(victim.deviceId().toString())
                        .doesNotContain("Private Novel");
            }

            assertThat(status)
                    .as("%s %s answered %s; a stranger must never get a server error either",
                            route.method(), route.pattern(), status)
                    .isNotEqualTo(500);
        }

        assertThat(leaks)
                .as("these routes served another account's data")
                .isEmpty();
        assertThat(selfScoped)
                .as("the sweep must have exercised the caller-scoped collections too")
                .isNotEmpty();

        // Silently skipping a route it cannot fill is how this test quietly stops covering
        // anything: eight routes went unswept that way, four of them added the same week.
        assertThat(unfillable)
                .as("""
                    these routes were skipped because the sweep cannot fill their path                     variables, so nothing checks their authorization. Teach fill() about                     them rather than leaving them unswept.""")
                .isEmpty();

        // Nothing the sweep did may have altered the victim's project.
        assertThat(jdbc.sql("SELECT title FROM project WHERE id = :id AND deleted_at IS NULL")
                .param("id", projectId).query(String.class).optional())
                .as("a rejected request must not modify or delete anything")
                .contains("Private Novel");
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id AND deleted_at IS NULL")
                .param("id", itemId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("no route is reachable without a token at all")
    void anonymousCannotReachAnyRoute() throws Exception {
        for (Route route : mappedRoutes()) {
            String path = fill(route.pattern(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID());
            assertThat(path).as("%s has an unfillable path variable", route.pattern())
                    .doesNotContain("{");

            MvcResult result = mvc.perform(MockMvcRequestBuilders.request(route.method(), path)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andReturn();

            assertThat(result.getResponse().getStatus())
                    .as("%s %s must demand authentication", route.method(), route.pattern())
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("the sweep covers the routes we expect it to")
    void sweepCoversKnownRoutes() {
        List<String> paths = mappedRoutes().stream().map(Route::pattern).distinct().sorted().toList();
        assertThat(paths).contains(
                "/api/v1/projects",
                "/api/v1/projects/{projectId}",
                "/api/v1/projects/{projectId}/binder",
                "/api/v1/projects/{projectId}/sync",
                "/api/v1/projects/{projectId}/conflicts",
                "/api/v1/projects/{projectId}/compile/formats",
                "/api/v1/devices");
    }
}

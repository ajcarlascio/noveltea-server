package com.noveltea.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.binder.BinderService;
import com.noveltea.project.ProjectExceptions.ProjectNotDeleted;
import com.noveltea.support.AbstractPostgresTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ProjectServiceTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired ProjectService projects;
    @Autowired ProjectAccess access;
    @Autowired AuthService auth;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private Session someone() {
        return auth.register("user-" + UUID.randomUUID() + "@example.com", PASSWORD, "Laptop", "web");
    }

    private long rowsFor(UUID projectId) {
        return jdbc.sql("SELECT count(*) FROM project WHERE id = :id")
                .param("id", projectId).query(Long.class).single();
    }

    // ---------------------------------------------------------------- create

    @Test
    @DisplayName("creating stores the caller as owner and defaults settings to an object")
    void createSetsOwnerAndDefaults() {
        Session me = someone();
        Project project = projects.create(me.userId(), "  The Lighthouse  ", null);

        assertThat(project.ownerId()).isEqualTo(me.userId());
        assertThat(project.title()).as("titles are trimmed").isEqualTo("The Lighthouse");
        assertThat(project.settings().isObject()).isTrue();
        assertThat(project.deletedAt()).isNull();

        UUID storedOwner = jdbc.sql("SELECT owner_id FROM project WHERE id = :id")
                .param("id", project.id()).query(UUID.class).single();
        assertThat(storedOwner).isEqualTo(me.userId());
    }

    @Test
    @DisplayName("settings round-trip as JSON")
    void settingsRoundTrip() {
        Session me = someone();
        ObjectNode settings = mapper.createObjectNode();
        settings.put("wordCountTarget", 90000);
        settings.putObject("compile").put("separator", "***");

        Project created = projects.create(me.userId(), "Novel", settings);
        Project reloaded = projects.get(created.id(), false);

        assertThat(reloaded.settings().get("wordCountTarget").asInt()).isEqualTo(90000);
        assertThat(reloaded.settings().get("compile").get("separator").asText()).isEqualTo("***");
    }

    @Test
    @DisplayName("blank titles and non-object settings are refused")
    void invalidInputRefused() {
        Session me = someone();
        assertThatThrownBy(() -> projects.create(me.userId(), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("title");
        assertThatThrownBy(() -> projects.create(me.userId(), "   ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.create(me.userId(), "X", mapper.createArrayNode()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON object");
        assertThatThrownBy(() -> projects.create(null, "X", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("ownerId");
        assertThat(projects.list(me.userId())).isEmpty();
    }

    @Test
    @DisplayName("an over-long title is refused rather than truncated")
    void overlongTitleRefused() {
        Session me = someone();
        assertThatThrownBy(() -> projects.create(me.userId(), "x".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
    }

    // ------------------------------------------------------------------ read

    @Test
    @DisplayName("listing shows only the caller's own live projects")
    void listingIsScopedToOwner() {
        Session me = someone();
        Session other = someone();
        projects.create(me.userId(), "Mine A", null);
        projects.create(me.userId(), "Mine B", null);
        projects.create(other.userId(), "Theirs", null);

        assertThat(projects.list(me.userId())).extracting(Project::title)
                .containsExactlyInAnyOrder("Mine A", "Mine B");
        assertThat(projects.list(other.userId())).extracting(Project::title).containsExactly("Theirs");
    }

    @Test
    @DisplayName("document and word counts come back with the project")
    void countsAreAggregated() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);

        UUID one = binder.create(project.id(), me.deviceId(), null, "document", "One", null);
        UUID two = binder.create(project.id(), me.deviceId(), null, "document", "Two", one);
        for (UUID id : new UUID[] {one, two}) {
            jdbc.sql("INSERT INTO document (id, word_count) VALUES (:id, 1200)")
                    .param("id", id).update();
        }

        Project reloaded = projects.get(project.id(), false);
        assertThat(reloaded.documentCount()).isEqualTo(2);
        assertThat(reloaded.wordCount()).isEqualTo(2400);
    }

    @Test
    @DisplayName("a project with no documents reports zero, not null")
    void emptyProjectCountsAreZero() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Empty", null);
        assertThat(projects.get(project.id(), false).documentCount()).isZero();
        assertThat(projects.get(project.id(), false).wordCount()).isZero();
    }

    // ---------------------------------------------------------------- update

    @Test
    @DisplayName("a partial update leaves omitted fields alone")
    void partialUpdateLeavesOtherFields() {
        Session me = someone();
        ObjectNode settings = mapper.createObjectNode();
        settings.put("wordCountTarget", 50000);
        Project project = projects.create(me.userId(), "Working Title", settings);

        Project renamed = projects.update(project.id(), "Final Title", null);
        assertThat(renamed.title()).isEqualTo("Final Title");
        assertThat(renamed.settings().get("wordCountTarget").asInt())
                .as("omitting settings must not wipe them")
                .isEqualTo(50000);

        ObjectNode newSettings = mapper.createObjectNode();
        newSettings.put("wordCountTarget", 80000);
        Project resettled = projects.update(project.id(), null, newSettings);
        assertThat(resettled.title()).as("omitting the title must not blank it").isEqualTo("Final Title");
        assertThat(resettled.settings().get("wordCountTarget").asInt()).isEqualTo(80000);
    }

    @Test
    @DisplayName("updating with a blank title is refused")
    void updateRejectsBlankTitle() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Keep Me", null);
        assertThatThrownBy(() -> projects.update(project.id(), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(projects.get(project.id(), false).title()).isEqualTo("Keep Me");
    }

    // -------------------------------------------------------------- deletion

    @Test
    @DisplayName("deleting hides the project but destroys nothing")
    void deleteIsReversible() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);
        UUID item = binder.create(project.id(), me.deviceId(), null, "document", "Chapter", null);

        projects.delete(project.id());

        assertThat(projects.list(me.userId())).isEmpty();
        assertThat(projects.listDeleted(me.userId())).extracting(Project::title).containsExactly("Novel");
        assertThat(rowsFor(project.id())).as("the row must still exist").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", item).query(Long.class).single())
                .as("contents are untouched by a delete")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("restoring brings the project back into the list")
    void restoreReturnsProject() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);
        projects.delete(project.id());

        Project restored = projects.restore(project.id());

        assertThat(restored.deletedAt()).isNull();
        assertThat(projects.list(me.userId())).extracting(Project::title).containsExactly("Novel");
        assertThat(projects.listDeleted(me.userId())).isEmpty();
    }

    @Test
    @DisplayName("a live project cannot be purged — deletion must come first")
    void purgeRefusesLiveProject() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);

        assertThatThrownBy(() -> projects.purge(project.id()))
                .isInstanceOf(ProjectNotDeleted.class);
        assertThat(rowsFor(project.id()))
                .as("one mistyped request must not be able to destroy a novel")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("purging a deleted project removes it and everything inside it")
    void purgeCascades() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);
        UUID item = binder.create(project.id(), me.deviceId(), null, "document", "Chapter", null);
        jdbc.sql("INSERT INTO document (id, word_count) VALUES (:id, 10)").param("id", item).update();

        projects.delete(project.id());
        projects.purge(project.id());

        assertThat(rowsFor(project.id())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                .param("id", item).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM document WHERE id = :id")
                .param("id", item).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a deleted project is invisible to the normal access check but reachable for restore")
    void deletedProjectAccessRules() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);
        CurrentUser principal = new CurrentUser(me.userId(), me.deviceId());

        access.requireReadable(principal, project.id());
        projects.delete(project.id());

        assertThatThrownBy(() -> access.requireReadable(principal, project.id()))
                .isInstanceOf(AccessDenied.class);
        access.requireOwnerIncludingDeleted(principal, project.id());
    }

    @Test
    @DisplayName("one owner cannot see, restore or purge another's project")
    void crossOwnerAccessRefused() {
        Session owner = someone();
        Session stranger = someone();
        Project project = projects.create(owner.userId(), "Secret", null);
        CurrentUser strangerPrincipal = new CurrentUser(stranger.userId(), stranger.deviceId());

        assertThatThrownBy(() -> access.requireReadable(strangerPrincipal, project.id()))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> access.requireOwnerIncludingDeleted(strangerPrincipal, project.id()))
                .isInstanceOf(AccessDenied.class);
        assertThat(projects.listDeleted(stranger.userId())).isEmpty();
    }

    @Test
    @DisplayName("fetching a project that does not exist is refused, not null")
    void missingProjectIsRefused() {
        assertThatThrownBy(() -> projects.get(UUID.randomUUID(), false))
                .isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> projects.get(null, false))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("projectId");
    }

    @Test
    @DisplayName("deleting twice is harmless")
    void doubleDeleteIsIdempotent() {
        Session me = someone();
        Project project = projects.create(me.userId(), "Novel", null);
        projects.delete(project.id());
        projects.delete(project.id());
        assertThat(projects.listDeleted(me.userId())).hasSize(1);
    }
}

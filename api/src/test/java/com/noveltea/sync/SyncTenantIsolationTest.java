package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.auth.AuthService;
import com.noveltea.binder.BinderService;
import com.noveltea.snapshot.SnapshotService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A push is authorized on its project, but every write keys on the client-supplied entity
 * id. These pin that an id belonging to somebody else's project is refused.
 *
 * <p>The route check is not enough on its own: it proves the caller may write to THIS
 * project, not that the row they named is in it. Without scoping, knowing a document id —
 * from an export filename, a bug report, a co-author's device — is enough to overwrite
 * another author's chapter, and the change is recorded against the attacker's project so
 * the victim's own devices never learn anything happened.
 */
class SyncTenantIsolationTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired SnapshotService snapshots;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;

    /** A second account with its own project, standing in for the victim. */
    private record Victim(UUID projectId, UUID documentId, UUID folderId) {}

    private Victim seedVictim() {
        UUID victimUser = auth.register("victim-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "Laptop", "web").userId();
        UUID victimProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Their Novel')")
                .param("id", victimProject).param("o", victimUser).update();

        UUID folder = binder.create(victimProject, deviceB, null, "folder", "Their Act One", null);
        UUID document = binder.create(victimProject, deviceB, folder, "document", "Their Chapter", null);
        jdbc.sql("""
                INSERT INTO document (id, content, search_text, word_count)
                VALUES (:id, CAST(:c AS jsonb), 'their precious prose', 3)
                """)
                .param("id", document).param("c", doc("their precious prose")).update();
        return new Victim(victimProject, document, folder);
    }

    private ChangeRequest documentUpdate(UUID id, Long base, String text) {
        ObjectNode data = mapper.createObjectNode();
        try {
            data.set("content", mapper.readTree(doc(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new ChangeRequest("document", id, "update", base, data);
    }

    private String victimContent(UUID documentId) {
        return jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", documentId).query(String.class).single();
    }

    @Test
    @DisplayName("a document in another project cannot be overwritten through this project's push")
    void cannotOverwriteAnotherProjectsDocument() {
        Victim victim = seedVictim();
        long realVersion = jdbc.sql("SELECT version FROM document WHERE id = :id")
                .param("id", victim.documentId()).query(Long.class).single();

        // projectId here is the attacker's own project — authorized correctly.
        PushResponse response = sync.push(projectId, deviceA,
                List.of(documentUpdate(victim.documentId(), realVersion, "overwritten by a stranger")));

        assertThat(victimContent(victim.documentId()))
                .as("an author's chapter must not be reachable from another project's push")
                .contains("their precious prose")
                .doesNotContain("overwritten by a stranger");
        assertThat(response.applied()).isEmpty();
    }

    @Test
    @DisplayName("a failed cross-project push does not disclose the real version")
    void doesNotLeakForeignVersion() {
        Victim victim = seedVictim();

        PushResponse response = sync.push(projectId, deviceA,
                List.of(documentUpdate(victim.documentId(), 1L, "probe")));

        assertThat(response.conflicts()).allSatisfy(conflict ->
                assertThat(conflict.serverVersion())
                        .as("returning the true version turns a refusal into an oracle")
                        .isNull());
    }

    @Test
    @DisplayName("a cross-project push does not copy another project's title into a conflict copy")
    void doesNotLeakForeignTitle() {
        Victim victim = seedVictim();

        sync.push(projectId, deviceA, List.of(documentUpdate(victim.documentId(), 1L, "probe")));

        assertThat(jdbc.sql("SELECT coalesce(string_agg(title, ' | '), '') FROM binder_item WHERE project_id = :p")
                .param("p", projectId).query(String.class).single())
                .as("a conflict copy must not carry a foreign document's title into this project")
                .doesNotContain("Their Chapter");
    }

    @Test
    @DisplayName("a folder in another project cannot be reparented, which would detach its subtree")
    void cannotReparentAnotherProjectsFolder() {
        Victim victim = seedVictim();
        ObjectNode data = mapper.createObjectNode();
        data.put("parent_id", (String) null);
        data.put("title", "seized");

        sync.push(projectId, deviceA,
                List.of(new ChangeRequest("binder_item", victim.folderId(), "update", 1L, data)));

        assertThat(jdbc.sql("SELECT title FROM binder_item WHERE id = :id")
                .param("id", victim.folderId()).query(String.class).single())
                .as("reparenting a foreign folder detaches a whole subtree from its binder")
                .isEqualTo("Their Act One");
    }

    @Test
    @DisplayName("a snapshot in another project cannot be deleted")
    void cannotDeleteAnotherProjectsSnapshot() {
        Victim victim = seedVictim();
        UUID snapshotId = snapshots.capture(victim.documentId(), "their milestone", false, deviceB);

        sync.push(projectId, deviceA,
                List.of(new ChangeRequest("snapshot", snapshotId, "delete", 1L, null)));

        assertThat(jdbc.sql("SELECT count(*) FROM snapshot WHERE id = :id")
                .param("id", snapshotId).query(Long.class).single())
                .as("a delete keyed on id alone destroys history in any project")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a spec-driven entity in another project cannot be deleted or updated")
    void cannotTouchAnotherProjectsTaxonomy() {
        Victim victim = seedVictim();
        UUID labelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO taxonomy (id, project_id, kind, name, order_key)
                VALUES (:id, :p, 'label', 'Their Label', 'V')
                """)
                .param("id", labelId).param("p", victim.projectId()).update();

        ObjectNode rename = mapper.createObjectNode();
        rename.put("name", "seized");
        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("taxonomy", labelId, "update", 1L, rename)));
        sync.push(projectId, deviceA, List.of(
                new ChangeRequest("taxonomy", labelId, "delete", 1L, null)));

        assertThat(jdbc.sql("SELECT count(*) FROM taxonomy WHERE id = :id AND name = 'Their Label'")
                .param("id", labelId).query(Long.class).single())
                .as("the generic writer deletes by id alone, in any project")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a normal push into one's own project still works")
    void ownProjectPushStillWorks() {
        UUID mine = seedDocument("My Chapter", "V", "my prose");

        PushResponse response = sync.push(projectId, deviceA,
                List.of(documentUpdate(mine, 1L, "my revision")));

        assertThat(response.conflicts()).isEmpty();
        assertThat(victimContent(mine)).contains("my revision");
    }
}

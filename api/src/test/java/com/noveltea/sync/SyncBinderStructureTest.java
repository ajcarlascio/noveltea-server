package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Structural safety on the sync push path.
 *
 * <p>`BinderService.move` refuses cycles, refuses cross-project parents, and serialises
 * moves. A reparent arriving over sync must obey exactly the same rules — it is the same
 * operation, reached a different way. A cycle here has no root, so the subtree renders
 * nowhere on any device, and the change propagates: one request can make an author's
 * manuscript unreachable everywhere at once.
 */
class SyncBinderStructureTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private ChangeRequest reparent(UUID itemId, UUID newParent, long baseVersion) {
        ObjectNode data = mapper.createObjectNode();
        data.put("parent_id", newParent == null ? null : newParent.toString());
        return new ChangeRequest("binder_item", itemId, "update", baseVersion, data);
    }

    /** Items a client can actually reach by walking down from the roots. */
    private long reachable() {
        return jdbc.sql("""
                WITH RECURSIVE r AS (
                    SELECT id FROM binder_item
                     WHERE project_id = :p AND parent_id IS NULL AND deleted_at IS NULL
                    UNION
                    SELECT b.id FROM binder_item b JOIN r ON b.parent_id = r.id
                     WHERE b.deleted_at IS NULL
                )
                SELECT count(*) FROM r
                """).param("p", projectId).query(Long.class).single();
    }

    private long live() {
        return jdbc.sql("SELECT count(*) FROM binder_item WHERE project_id = :p AND deleted_at IS NULL")
                .param("p", projectId).query(Long.class).single();
    }

    @Test
    @DisplayName("A REPARENT UNDER ITS OWN DESCENDANT IS REFUSED")
    void cannotReparentUnderOwnDescendant() {
        UUID act = binder.create(projectId, deviceA, null, "folder", "Act I", null);
        UUID chapter = binder.create(projectId, deviceA, act, "folder", "Chapter One", null);
        binder.create(projectId, deviceA, chapter, "document", "Scene", null);

        // An explicit, deliberately distinct order_key. Without one this test passes for the
        // wrong reason: the write fails on the sibling-order unique index rather than on the
        // cycle check, so it stays green even with the guard removed.
        ObjectNode data = mapper.createObjectNode();
        data.put("parent_id", chapter.toString());
        data.put("order_key", "zzzz");

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("binder_item", act, "update", 1L, data)));

        assertThat(jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", act).query(UUID.class).optional())
                .as("the reparent must not have happened at all")
                .isEmpty();
        assertThat(response.applied()).as("a cycle must not be accepted").isEmpty();
        assertThat(reachable())
                .as("a cyclic subtree has no root, so it renders nowhere on any device")
                .isEqualTo(live());
    }

    @Test
    @DisplayName("an item cannot be made its own parent")
    void cannotSelfParent() {
        UUID act = binder.create(projectId, deviceA, null, "folder", "Act I", null);

        sync.push(projectId, deviceA, List.of(reparent(act, act, 1L)));

        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = parent_id")
                .query(Long.class).single()).isZero();
        assertThat(reachable()).isEqualTo(live());
    }

    @Test
    @DisplayName("a parent in another project is refused")
    void cannotReparentAcrossProjects() {
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID foreignItem = binder.create(otherProject, deviceB, null, "folder", "Their Act", null);

        UUID mine = binder.create(projectId, deviceA, null, "document", "Mine", null);
        sync.push(projectId, deviceA, List.of(reparent(mine, foreignItem, 1L)));

        assertThat(jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", mine).query(UUID.class).optional())
                .as("a parent outside the project detaches the item from its own binder")
                .isEmpty();
    }

    @Test
    @DisplayName("a malformed reparent is a reported conflict, not a 500 that aborts the batch")
    void constraintViolationIsReported() {
        UUID first = binder.create(projectId, deviceA, null, "document", "First", null);
        UUID second = binder.create(projectId, deviceA, null, "document", "Second", null);

        ObjectNode ghost = mapper.createObjectNode();
        ghost.put("parent_id", UUID.randomUUID().toString());

        PushResponse response = sync.push(projectId, deviceA, List.of(
                new ChangeRequest("binder_item", first, "update", 1L, ghost),
                new ChangeRequest("binder_item", second, "update", 1L,
                        mapper.createObjectNode().put("title", "Renamed"))));

        assertThat(response.conflicts()).as("the bad change is reported").hasSize(1);
        assertThat(jdbc.sql("SELECT title FROM binder_item WHERE id = :id")
                .param("id", second).query(String.class).single())
                .as("a later change in the batch must still be applied")
                .isEqualTo("Renamed");
    }

    @Test
    @DisplayName("a legitimate reparent over sync still works")
    void legitimateReparentStillWorks() {
        UUID actOne = binder.create(projectId, deviceA, null, "folder", "Act I", null);
        UUID actTwo = binder.create(projectId, deviceA, null, "folder", "Act II", null);
        UUID scene = binder.create(projectId, deviceA, actOne, "document", "Scene", null);

        PushResponse response = sync.push(projectId, deviceA, List.of(reparent(scene, actTwo, 1L)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", scene).query(UUID.class).single()).isEqualTo(actTwo);
    }
}

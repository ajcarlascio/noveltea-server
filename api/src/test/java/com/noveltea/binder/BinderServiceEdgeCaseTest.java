package com.noveltea.binder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.binder.BinderExceptions.CrossProjectMove;
import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Gaps not covered by BinderServiceTest, several of which are failure modes rather than features. */
class BinderServiceEdgeCaseTest extends AbstractPostgresTest {

    @Autowired BinderService binder;

    private UUID folder(String title, UUID parent, UUID after) {
        return binder.create(projectId, deviceA, parent, "folder", title, after);
    }

    private UUID parentOf(UUID id) {
        return jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", id).query(UUID.class).optional().orElse(null);
    }

    private UUID trashId() {
        return jdbc.sql("SELECT id FROM binder_item WHERE project_id = :p AND type = 'trash'")
                .param("p", projectId).query(UUID.class).single();
    }

    @Test
    @DisplayName("restoring an item that was never trashed must not relocate it")
    void restoringALiveItemIsANoOp() {
        UUID act = folder("Act I", null, null);
        UUID scene = folder("Scene", act, null);

        binder.restore(scene, deviceA);

        assertThat(parentOf(scene))
                .as("a stray restore must not silently move a live item to the root")
                .isEqualTo(act);
    }

    @Test
    @DisplayName("moving an item under another project's item is rejected")
    void crossProjectMoveIsRejected() {
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :owner, 'Other')")
                .param("id", otherProject).param("owner", userId).update();
        UUID foreign = binder.create(otherProject, deviceA, null, "folder", "Foreign", null);
        UUID mine = folder("Mine", null, null);

        assertThatThrownBy(() -> binder.move(mine, foreign, null, deviceA))
                .isInstanceOf(CrossProjectMove.class);
        assertThat(parentOf(mine)).isNull();
    }

    @Test
    @DisplayName("moving a folder carries its whole subtree")
    void movingAFolderCarriesDescendants() {
        UUID actOne = folder("Act I", null, null);
        UUID actTwo = folder("Act II", null, actOne);
        UUID chapter = folder("Chapter", actOne, null);
        UUID scene = folder("Scene", chapter, null);

        binder.move(chapter, actTwo, null, deviceA);

        assertThat(parentOf(chapter)).isEqualTo(actTwo);
        assertThat(parentOf(scene)).as("grandchild follows without being touched").isEqualTo(chapter);
        assertThat(binder.tree(projectId).stream().map(BinderNode::title))
                .contains("Act I", "Act II", "Chapter", "Scene");
    }

    @Test
    @DisplayName("trashing an already-trashed item is idempotent and keeps the original origin")
    void trashingTwiceKeepsTheOriginalOrigin() {
        UUID act = folder("Act I", null, null);
        UUID scene = folder("Scene", act, null);

        binder.trash(scene, deviceA);
        binder.trash(scene, deviceA);

        assertThat(parentOf(scene)).isEqualTo(trashId());
        UUID origin = jdbc.sql("SELECT trashed_from_parent_id FROM binder_item WHERE id = :id")
                .param("id", scene).query(UUID.class).optional().orElse(null);
        assertThat(origin)
                .as("second trash must not overwrite the origin with the trash node itself")
                .isEqualTo(act);

        binder.restore(scene, deviceA);
        assertThat(parentOf(scene)).isEqualTo(act);
    }

    @Test
    @DisplayName("emptying the trash reaches arbitrary depth")
    void emptyTrashReachesDeepDescendants() {
        UUID a = folder("A", null, null);
        UUID b = folder("B", a, null);
        UUID c = folder("C", b, null);
        UUID d = folder("D", c, null);
        binder.trash(a, deviceA);

        int deleted = binder.emptyTrash(projectId, deviceA);

        assertThat(deleted).as("four levels").isEqualTo(4);
        for (UUID id : List.of(a, b, c, d)) {
            assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                    .param("id", id).query(java.time.OffsetDateTime.class).optional()).isPresent();
        }
    }

    @Test
    @DisplayName("emptying the trash does not tombstone the trash node itself")
    void emptyTrashKeepsTheTrashNode() {
        UUID act = folder("Act I", null, null);
        binder.trash(act, deviceA);
        UUID trash = trashId();

        binder.emptyTrash(projectId, deviceA);

        assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                .param("id", trash).query(java.time.OffsetDateTime.class).optional())
                .as("the trash node must survive so the next delete has somewhere to go")
                .isEmpty();
    }

    @Test
    @DisplayName("the trash node itself cannot be trashed")
    void trashNodeCannotBeTrashed() {
        UUID trash = binder.ensureTrash(projectId, deviceA);
        assertThatThrownBy(() -> binder.trash(trash, deviceA))
                .as("must fail for the right reason, not merely fail")
                .isInstanceOf(BinderExceptions.BinderCycle.class);
        assertThat(parentOf(trash)).isNull();
    }

    @Test
    @DisplayName("positioning after a sibling of a DIFFERENT parent does not corrupt ordering")
    void createAfterForeignSiblingStillOrdersCorrectly() {
        UUID actOne = folder("Act I", null, null);
        UUID actTwo = folder("Act II", null, actOne);
        UUID inOne = folder("In One", actOne, null);

        // Caller passes a sibling that lives under a different parent — a plausible
        // client bug. The result must still be a valid ordering under actTwo.
        UUID created = binder.create(projectId, deviceA, actTwo, "folder", "In Two", inOne);

        assertThat(parentOf(created)).isEqualTo(actTwo);
        long distinct = jdbc.sql("""
                SELECT count(DISTINCT order_key) FROM binder_item WHERE parent_id = :p AND deleted_at IS NULL
                """).param("p", actTwo).query(Long.class).single();
        long total = jdbc.sql("""
                SELECT count(*) FROM binder_item WHERE parent_id = :p AND deleted_at IS NULL
                """).param("p", actTwo).query(Long.class).single();
        assertThat(distinct).as("no duplicate ordering keys among siblings").isEqualTo(total);
    }

    @Test
    @DisplayName("tree() excludes tombstoned items but keeps trashed ones visible")
    void treeShowsTrashedButNotDeleted() {
        UUID kept = folder("Kept", null, null);
        UUID trashed = folder("Trashed", null, kept);
        binder.trash(trashed, deviceA);

        assertThat(binder.tree(projectId).stream().map(BinderNode::title))
                .as("trashed items must remain visible so the author can restore them")
                .contains("Trashed", "Kept", "Trash");

        binder.emptyTrash(projectId, deviceA);
        assertThat(binder.tree(projectId).stream().map(BinderNode::title))
                .doesNotContain("Trashed")
                .contains("Kept");
    }
}

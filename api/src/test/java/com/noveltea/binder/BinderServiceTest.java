package com.noveltea.binder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.noveltea.binder.BinderExceptions.BinderCycle;
import com.noveltea.binder.BinderExceptions.BinderItemNotFound;
import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BinderServiceTest extends AbstractPostgresTest {

    @Autowired BinderService binder;

    private UUID folder(String title, UUID parent, UUID after) {
        return binder.create(projectId, deviceA, parent, "folder", title, after);
    }

    private UUID document(String title, UUID parent, UUID after) {
        return binder.create(projectId, deviceA, parent, "document", title, after);
    }

    private UUID parentOf(UUID id) {
        return jdbc.sql("SELECT parent_id FROM binder_item WHERE id = :id")
                .param("id", id).query(UUID.class).optional().orElse(null);
    }

    private long changeCount() {
        return jdbc.sql("SELECT count(*) FROM change_log WHERE project_id = :p")
                .param("p", projectId).query(Long.class).single();
    }

    private List<String> childTitlesInOrder(UUID parent) {
        return jdbc.sql("""
                SELECT title FROM binder_item
                 WHERE project_id = :p AND parent_id IS NOT DISTINCT FROM CAST(:parent AS uuid)
                   AND deleted_at IS NULL
                 ORDER BY order_key
                """)
                .param("p", projectId).param("parent", parent)
                .query(String.class).list();
    }

    // ------------------------------------------------------------ cycles

    @Test
    @DisplayName("an item cannot be moved under its own child")
    void rejectsDirectCycle() {
        UUID act = folder("Act I", null, null);
        UUID scene = folder("Scene 1", act, null);

        assertThatThrownBy(() -> binder.move(act, scene, null, deviceA))
                .isInstanceOf(BinderCycle.class);

        assertThat(parentOf(act)).as("a rejected move must change nothing").isNull();
        assertThat(parentOf(scene)).isEqualTo(act);
    }

    @Test
    @DisplayName("an item cannot be moved under a deep descendant")
    void rejectsDeepCycle() {
        UUID a = folder("A", null, null);
        UUID b = folder("B", a, null);
        UUID c = folder("C", b, null);
        UUID d = folder("D", c, null);

        assertThatThrownBy(() -> binder.move(a, d, null, deviceA)).isInstanceOf(BinderCycle.class);
        assertThat(parentOf(a)).isNull();
        assertThat(parentOf(d)).isEqualTo(c);
    }

    @Test
    @DisplayName("an item cannot be moved under itself")
    void rejectsSelfParenting() {
        UUID a = folder("A", null, null);
        assertThatThrownBy(() -> binder.move(a, a, null, deviceA)).isInstanceOf(BinderCycle.class);
    }

    @Test
    @DisplayName("a legitimate move between branches is allowed")
    void allowsNonCyclicMove() {
        UUID actOne = folder("Act I", null, null);
        UUID actTwo = folder("Act II", null, actOne);
        UUID scene = document("Scene", actOne, null);

        binder.move(scene, actTwo, null, deviceA);

        assertThat(parentOf(scene)).isEqualTo(actTwo);
        assertThat(childTitlesInOrder(actOne)).isEmpty();
        assertThat(childTitlesInOrder(actTwo)).containsExactly("Scene");
    }

    // ---------------------------------------------------------- ordering

    @Test
    @DisplayName("creation position is honoured and siblings stay ordered")
    void createsInRequestedPosition() {
        UUID one = document("One", null, null);
        UUID three = document("Three", null, one);
        document("Two", null, one); // between One and Three

        assertThat(childTitlesInOrder(null)).containsExactly("One", "Two", "Three");
        assertThat(three).isNotNull();
    }

    @Test
    @DisplayName("repeated reordering never collides or loses ordering")
    void repeatedReorderingStaysConsistent() {
        UUID a = document("A", null, null);
        UUID b = document("B", null, a);
        UUID c = document("C", null, b);

        // Shuffle repeatedly; the unique sibling-order index would reject a collision.
        for (int i = 0; i < 40; i++) {
            binder.move(c, null, a, deviceA); // A, C, B
            binder.move(b, null, null, deviceA); // B, A, C
            binder.move(a, null, c, deviceA); // B, C, A
        }

        assertThat(childTitlesInOrder(null)).containsExactly("B", "C", "A");
        long distinctKeys = jdbc.sql("""
                SELECT count(DISTINCT order_key) FROM binder_item
                 WHERE project_id = :p AND parent_id IS NULL
                """).param("p", projectId).query(Long.class).single();
        assertThat(distinctKeys).isEqualTo(3);
    }

    // ------------------------------------------------------------- trash

    @Test
    @DisplayName("trash is a move, and restore returns the item to where it came from")
    void trashAndRestoreRoundTrip() {
        UUID act = folder("Act I", null, null);
        UUID scene = document("Scene", act, null);

        binder.trash(scene, deviceA);

        UUID trashId = jdbc.sql("SELECT id FROM binder_item WHERE project_id = :p AND type = 'trash'")
                .param("p", projectId).query(UUID.class).single();
        assertThat(parentOf(scene)).as("trashing reparents, it does not delete").isEqualTo(trashId);
        assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                .param("id", scene).query(java.time.OffsetDateTime.class).optional())
                .as("deleted_at is reserved for emptying the trash")
                .isEmpty();

        binder.restore(scene, deviceA);
        assertThat(parentOf(scene)).isEqualTo(act);
    }

    @Test
    @DisplayName("restoring to a parent that is itself trashed falls back to root, never strands the item")
    void restoreFallsBackToRootWhenOriginalParentIsGone() {
        UUID act = folder("Act I", null, null);
        UUID scene = document("Scene", act, null);

        binder.trash(scene, deviceA);
        binder.trash(act, deviceA);
        binder.restore(scene, deviceA);

        assertThat(parentOf(scene)).as("restored to root rather than refused").isNull();
        assertThat(childTitlesInOrder(null)).contains("Scene");
    }

    @Test
    @DisplayName("emptying the trash tombstones the whole subtree")
    void emptyTrashTombstonesDescendants() {
        UUID act = folder("Act I", null, null);
        UUID scene = document("Scene", act, null);
        binder.trash(act, deviceA);

        int deleted = binder.emptyTrash(projectId, deviceA);

        assertThat(deleted).as("folder and its child").isEqualTo(2);
        for (UUID id : List.of(act, scene)) {
            assertThat(jdbc.sql("SELECT deleted_at FROM binder_item WHERE id = :id")
                    .param("id", id).query(java.time.OffsetDateTime.class).optional())
                    .as("row is retained with a tombstone so offline clients learn it is gone")
                    .isPresent();
        }
        assertThat(binder.tree(projectId).stream().map(BinderNode::title))
                .doesNotContain("Act I", "Scene");
    }

    @Test
    @DisplayName("emptying an empty or absent trash is a no-op")
    void emptyTrashIsSafeWhenNothingIsTrashed() {
        assertThat(binder.emptyTrash(projectId, deviceA)).isZero();
        binder.ensureTrash(projectId, deviceA);
        assertThat(binder.emptyTrash(projectId, deviceA)).isZero();
    }

    // -------------------------------------------------- mutation contract

    @Test
    @DisplayName("every mutation appends exactly one change_log row")
    void everyMutationIsRecorded() {
        assertThat(changeCount()).isZero();

        UUID act = folder("Act I", null, null);
        assertThat(changeCount()).as("create").isEqualTo(1);

        binder.rename(act, "Act One", deviceA);
        assertThat(changeCount()).as("rename").isEqualTo(2);

        UUID scene = document("Scene", act, null);
        assertThat(changeCount()).as("second create").isEqualTo(3);

        binder.move(scene, null, act, deviceA);
        assertThat(changeCount()).as("move").isEqualTo(4);

        long beforeTrash = changeCount();
        binder.trash(scene, deviceA);
        assertThat(changeCount())
                .as("trash records the move, plus the trash node's own creation")
                .isEqualTo(beforeTrash + 2);

        long beforeRestore = changeCount();
        binder.restore(scene, deviceA);
        assertThat(changeCount()).as("restore").isEqualTo(beforeRestore + 1);
    }

    @Test
    @DisplayName("mutations bump version and stamp the originating device")
    void mutationsBumpVersionAndAttributeDevice() {
        UUID act = folder("Act I", null, null);

        binder.rename(act, "Act One", deviceB);

        var row = jdbc.sql("SELECT version, updated_by_device_id FROM binder_item WHERE id = :id")
                .param("id", act).query().singleRow();
        assertThat(((Number) row.get("version")).longValue()).isEqualTo(2);
        assertThat(row.get("updated_by_device_id")).isEqualTo(deviceB);
    }

    @Test
    @DisplayName("operating on an unknown item fails loudly")
    void unknownItemIsRejected() {
        assertThatThrownBy(() -> binder.rename(UUID.randomUUID(), "x", deviceA))
                .isInstanceOf(BinderItemNotFound.class);
    }
}

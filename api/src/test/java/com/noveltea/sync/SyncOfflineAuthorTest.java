package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ordinary case: one author, one device, a long spell offline.
 *
 * <p>This must stay boring. Conflict copies exist for the rare case where two devices
 * diverge; nothing here should ever touch that path. If a test in this class starts
 * reporting conflicts, the sync design has become too clever.
 */
class SyncOfflineAuthorTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;

    private ChangeRequest update(UUID id, Long baseVersion, String text) {
        ObjectNode data = mapper.createObjectNode();
        try {
            data.set("content", mapper.readTree(doc(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        data.put("search_text", text);
        return new ChangeRequest("document", id, "update", baseVersion, data);
    }

    @Test
    @DisplayName("a week offline pushes as ONE change and is simply accepted")
    void offlineWorkPushesCleanly() {
        UUID docId = seedDocument("Chapter One", "V", "before the trip");

        // Offline, the client coalesced hundreds of autosaves into one pending row whose
        // base_version is still the last version the server handed it.
        PushResponse response = sync.push(projectId, deviceA,
                List.of(update(docId, 1L, "an entire week of writing")));

        assertThat(response.conflicts()).as("no other device wrote; nothing to conflict with").isEmpty();
        assertThat(response.applied()).singleElement().satisfies(a -> assertThat(a.version()).isEqualTo(2));
        assertThat(jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", docId).query(String.class).single())
                .contains("an entire week of writing");
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single())
                .as("a clean push must not create a copy of anything")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("many sequential online saves just increment the version, one round trip each")
    void sequentialSavesNeverConflict() {
        UUID docId = seedDocument("Chapter One", "V", "draft 0");

        long version = 1;
        for (int i = 1; i <= 25; i++) {
            PushResponse response = sync.push(projectId, deviceA, List.of(update(docId, version, "draft " + i)));
            assertThat(response.conflicts()).isEmpty();
            version = response.applied().get(0).version();
            assertThat(version).isEqualTo(i + 1);
        }

        assertThat(jdbc.sql("SELECT count(*) FROM binder_item").query(Long.class).single())
                .as("25 saves must leave exactly one document in the binder")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("two devices that never overlap both push cleanly")
    void alternatingDevicesDoNotConflict() {
        UUID docId = seedDocument("Chapter One", "V", "start");

        // Device A writes, syncs. Device B pulls, then writes from what it just learned.
        PushResponse a = sync.push(projectId, deviceA, List.of(update(docId, 1L, "from the laptop")));
        long afterA = a.applied().get(0).version();

        PushResponse b = sync.push(projectId, deviceB, List.of(update(docId, afterA, "from the phone")));

        assertThat(a.conflicts()).isEmpty();
        assertThat(b.conflicts()).as("B based its edit on A's version; that is not a conflict").isEmpty();
        assertThat(b.applied().get(0).version()).isEqualTo(afterA + 1);
        assertThat(jdbc.sql("SELECT count(*) FROM document").query(Long.class).single()).isEqualTo(1);
    }
}

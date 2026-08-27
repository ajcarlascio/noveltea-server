package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * The fields on a document row that are not its prose.
 *
 * <p>A document is written by more than one pane. The editor saves content and knows
 * nothing about the index card; the corkboard saves a synopsis and hands the body back
 * untouched. Each sends only the fields it understands, so the rule this class exists to
 * hold is that <b>a field nobody mentioned is a field nobody changed</b> — otherwise
 * whichever pane saved last quietly erases the other, and an older client erases both on
 * every keystroke.
 */
class SyncDocumentFieldsTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** What the editor sends: prose and the things derived from prose. Nothing else. */
    private ObjectNode contentOnly(String text) {
        ObjectNode data = mapper.createObjectNode();
        data.set("content", parse(doc(text)));
        data.put("search_text", text);
        data.put("word_count", text.split("\\s+").length);
        return data;
    }

    private ChangeRequest update(UUID id, Long base, ObjectNode data) {
        return new ChangeRequest("document", id, "update", base, data);
    }

    private String synopsisOf(UUID id) {
        return jdbc.sql("SELECT synopsis FROM document WHERE id = :id")
                .param("id", id).query(String.class).optional().orElse(null);
    }

    private String contentOf(UUID id) {
        return jdbc.sql("SELECT content::text FROM document WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    @Test
    @DisplayName("a pushed synopsis is stored")
    void aSynopsisSurvivesThePush() {
        UUID docId = seedDocument("Chapter One", "V", "the lighthouse stood alone");

        ObjectNode data = contentOnly("the lighthouse stood alone");
        data.put("synopsis", "She climbs the tower and finds the lamp cold.");
        PushResponse response = sync.push(projectId, deviceA, List.of(update(docId, 1L, data)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(synopsisOf(docId)).isEqualTo("She climbs the tower and finds the lamp cold.");
    }

    @Test
    @DisplayName("a content save leaves a synopsis it never mentioned alone")
    void savingProseDoesNotEraseTheIndexCard() {
        UUID docId = seedDocument("Chapter One", "V", "first draft");
        jdbc.sql("UPDATE document SET synopsis = 'She climbs the tower.' WHERE id = :id")
                .param("id", docId).update();

        // Exactly what an editor save looks like — and what every client built before the
        // corkboard existed sends on every autosave.
        sync.push(projectId, deviceA, List.of(update(docId, 1L, contentOnly("second draft"))));

        assertThat(synopsisOf(docId))
                .as("an autosave from a client that has never heard of synopses must not wipe one")
                .isEqualTo("She climbs the tower.");
        assertThat(contentOf(docId)).contains("second draft");
    }

    @Test
    @DisplayName("a synopsis save leaves the prose alone")
    void savingTheIndexCardDoesNotDisturbTheProse() {
        UUID docId = seedDocument("Chapter One", "V", "the lighthouse stood alone");

        ObjectNode data = contentOnly("the lighthouse stood alone");
        data.put("synopsis", "She climbs the tower.");
        sync.push(projectId, deviceA, List.of(update(docId, 1L, data)));

        assertThat(contentOf(docId)).contains("the lighthouse stood alone");
    }

    @Test
    @DisplayName("an explicit null clears a synopsis, because deleting one has to be possible")
    void anExplicitNullClearsIt() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        jdbc.sql("UPDATE document SET synopsis = 'to be deleted' WHERE id = :id")
                .param("id", docId).update();

        // The distinction the whole design turns on: absent is not the same as null.
        ObjectNode data = contentOnly("prose");
        data.putNull("synopsis");
        sync.push(projectId, deviceA, List.of(update(docId, 1L, data)));

        assertThat(synopsisOf(docId)).isNull();
    }

    @Test
    @DisplayName("notes travel the same way, and are subject to the same rule")
    void notesBehaveLikeSynopses() {
        UUID docId = seedDocument("Chapter One", "V", "prose");

        ObjectNode withNotes = contentOnly("prose");
        withNotes.put("notes", "Check the tide tables for chapter three.");
        sync.push(projectId, deviceA, List.of(update(docId, 1L, withNotes)));

        sync.push(projectId, deviceA, List.of(update(docId, 2L, contentOnly("revised prose"))));

        assertThat(jdbc.sql("SELECT notes FROM document WHERE id = :id")
                .param("id", docId).query(String.class).optional())
                .contains("Check the tide tables for chapter three.");
    }

    @Test
    @DisplayName("a document created with a synopsis keeps it")
    void aCreateCarriesTheSynopsis() {
        UUID itemId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :projectId, 'document', 'Chapter Two', 'V', :device)
                """)
                .param("id", itemId).param("projectId", projectId).param("device", deviceA).update();

        ObjectNode data = contentOnly("new prose");
        data.put("synopsis", "The lamp goes out.");
        sync.push(projectId, deviceA,
                List.of(new ChangeRequest("document", itemId, "create", null, data)));

        assertThat(synopsisOf(itemId)).isEqualTo("The lamp goes out.");
    }

    @Test
    @DisplayName("the feed carries a synopsis back down, so another device can show the card")
    void theSynopsisComesBackInTheFeed() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        ObjectNode data = contentOnly("prose");
        data.put("synopsis", "She climbs the tower.");
        sync.push(projectId, deviceA, List.of(update(docId, 1L, data)));

        // Read from the change feed exactly as a second device would.
        var feed = sync.pull(projectId, deviceB, 0L, 100);
        assertThat(feed.changes())
                .filteredOn(c -> "document".equals(c.entityType()) && docId.equals(c.entityId()))
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.data().get("synopsis").asText())
                        .isEqualTo("She climbs the tower."));
    }
}

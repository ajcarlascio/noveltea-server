package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.binder.BinderService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The data entity types: taxonomy, custom metadata, collections and compile presets.
 *
 * <p>The recurring question here is whether malformed input can reach the database. Every
 * CHECK constraint in the schema has a matching check in {@code SyncEntitySpec}, and these
 * tests assert that violating one produces a reported conflict rather than an exception —
 * and that the row is genuinely absent afterwards.
 */
class SyncEntityTypesTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private ObjectNode data() {
        return mapper.createObjectNode();
    }

    private ChangeRequest change(String type, UUID id, String op, Long base, JsonNode data) {
        return new ChangeRequest(type, id, op, base, data);
    }

    private PushResponse push(ChangeRequest... changes) {
        return sync.push(projectId, deviceA, List.of(changes));
    }

    private long rows(String table, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }

    private Map<String, Object> row(String table, UUID id) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE id = :id")
                .param("id", id).query().singleRow();
    }

    // -------------------------------------------------------------- taxonomy

    @Test
    @DisplayName("a label is created, updated and soft-deleted through sync")
    void taxonomyLifecycle() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "label").put("name", "Needs Revision").put("color", "#cc3300").put("order_key", "V");

        PushResponse created = push(change("taxonomy", id, "create", null, create));
        assertThat(created.conflicts()).isEmpty();
        assertThat(row("taxonomy", id)).containsEntry("name", "Needs Revision")
                .containsEntry("kind", "label").containsEntry("color", "#cc3300");

        ObjectNode rename = data();
        rename.put("name", "Revised");
        PushResponse updated = push(change("taxonomy", id, "update", 1L, rename));
        assertThat(updated.applied()).singleElement().satisfies(a -> assertThat(a.version()).isEqualTo(2));
        assertThat(row("taxonomy", id))
                .as("a partial update must not blank the untouched columns")
                .containsEntry("name", "Revised").containsEntry("color", "#cc3300");

        push(change("taxonomy", id, "delete", 2L, null));
        assertThat(row("taxonomy", id).get("deleted_at"))
                .as("taxonomy has deleted_at, so delete is a tombstone rather than a removal")
                .isNotNull();
        assertThat(rows("taxonomy", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("a colour on a status is refused, not raised as a constraint violation")
    void colourOnlyAppliesToLabels() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "status").put("name", "Draft").put("color", "#ffffff").put("order_key", "V");

        PushResponse response = push(change("taxonomy", id, "create", null, create));

        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST);
            assertThat(c.detail()).contains("color");
        });
        assertThat(rows("taxonomy", id)).as("nothing may be written").isZero();
    }

    @Test
    @DisplayName("an unknown taxonomy kind is refused")
    void unknownEnumValueRefused() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "flavour").put("name", "X").put("order_key", "V");

        PushResponse response = push(change("taxonomy", id, "create", null, create));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("kind"));
        assertThat(rows("taxonomy", id)).isZero();
    }

    @Test
    @DisplayName("a missing required field is refused")
    void missingRequiredFieldRefused() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "label");

        PushResponse response = push(change("taxonomy", id, "create", null, create));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("name"));
        assertThat(rows("taxonomy", id)).isZero();
    }

    // ------------------------------------------------------------ collections

    @Test
    @DisplayName("a smart collection without a query is refused")
    void smartCollectionNeedsQuery() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("name", "Unfinished").put("is_smart", true).put("order_key", "V");

        PushResponse response = push(change("collection", id, "create", null, create));

        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("query"));
        assertThat(rows("collection", id)).isZero();
    }

    @Test
    @DisplayName("turning an existing collection smart is judged against the merged row")
    void invariantsUseTheMergedRowOnUpdate() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("name", "Manual").put("order_key", "V");
        push(change("collection", id, "create", null, create));

        ObjectNode makeSmart = data();
        makeSmart.put("is_smart", true);
        PushResponse refused = push(change("collection", id, "update", 1L, makeSmart));
        assertThat(refused.conflicts())
                .as("the patch alone looks fine; only the merged row shows the missing query")
                .hasSize(1);

        ObjectNode withQuery = data();
        withQuery.put("is_smart", true).putObject("query").put("status", "draft");
        PushResponse accepted = push(change("collection", id, "update", 1L, withQuery));
        assertThat(accepted.conflicts()).isEmpty();
        assertThat(row("collection", id)).containsEntry("is_smart", true);
    }

    @Test
    @DisplayName("a query that is not a JSON object is refused")
    void queryMustBeAnObject() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("name", "Bad").put("order_key", "V").putArray("query").add("nope");

        PushResponse response = push(change("collection", id, "create", null, create));
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("JSON object"));
    }

    // ------------------------------------------------- parent-scoped entities

    @Test
    @DisplayName("a collection item links a collection to a binder item")
    void collectionItemLifecycle() {
        UUID collectionId = UUID.randomUUID();
        ObjectNode collection = data();
        collection.put("name", "Act One").put("order_key", "V");
        push(change("collection", collectionId, "create", null, collection));

        UUID itemId = binder.create(projectId, deviceA, null, "document", "Scene", null);

        UUID linkId = UUID.randomUUID();
        ObjectNode link = data();
        link.put("collection_id", collectionId.toString())
                .put("binder_item_id", itemId.toString())
                .put("order_key", "V");

        PushResponse response = push(change("collection_item", linkId, "create", null, link));
        assertThat(response.conflicts()).isEmpty();
        assertThat(row("collection_item", linkId))
                .containsEntry("collection_id", collectionId)
                .containsEntry("binder_item_id", itemId);

        push(change("collection_item", linkId, "delete", 1L, null));
        assertThat(rows("collection_item", linkId))
                .as("collection_item has no deleted_at, so delete really removes it")
                .isZero();
    }

    @Test
    @DisplayName("a parent in ANOTHER project is refused — this is an authorization boundary")
    void parentMustBelongToTheSameProject() {
        // A second project owned by someone else entirely.
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :email)")
                .param("id", otherUser).param("email", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID foreignItem = binder.create(otherProject, deviceA, null, "document", "Theirs", null);

        UUID collectionId = UUID.randomUUID();
        ObjectNode collection = data();
        collection.put("name", "Mine").put("order_key", "V");
        push(change("collection", collectionId, "create", null, collection));

        UUID linkId = UUID.randomUUID();
        ObjectNode link = data();
        link.put("collection_id", collectionId.toString())
                .put("binder_item_id", foreignItem.toString())
                .put("order_key", "V");

        PushResponse response = push(change("collection_item", linkId, "create", null, link));

        assertThat(response.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(ConflictReason.INVALID_REQUEST);
            assertThat(c.detail())
                    .as("the message must not reveal that the row exists elsewhere")
                    .doesNotContain("Theirs");
        });
        assertThat(rows("collection_item", linkId)).isZero();
    }

    @Test
    @DisplayName("a malformed id is refused rather than reaching the database")
    void malformedUuidRefused() {
        UUID linkId = UUID.randomUUID();
        ObjectNode link = data();
        link.put("collection_id", "not-a-uuid").put("binder_item_id", "also-not").put("order_key", "V");

        PushResponse response = push(change("collection_item", linkId, "create", null, link));
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("valid id"));
    }

    @Test
    @DisplayName("custom metadata values attach to an item and a field")
    void customMetadataLifecycle() {
        UUID fieldId = UUID.randomUUID();
        ObjectNode field = data();
        field.put("name", "POV").put("field_type", "select").put("order_key", "V");
        field.putArray("options").add("first").add("third");
        assertThat(push(change("custom_metadata_field", fieldId, "create", null, field)).conflicts()).isEmpty();

        UUID itemId = binder.create(projectId, deviceA, null, "document", "Scene", null);

        UUID valueId = UUID.randomUUID();
        ObjectNode value = data();
        value.put("binder_item_id", itemId.toString()).put("field_id", fieldId.toString());
        value.put("value", "third");

        assertThat(push(change("custom_metadata_value", valueId, "create", null, value)).conflicts()).isEmpty();
        assertThat(row("custom_metadata_value", valueId)).containsEntry("field_id", fieldId);
    }

    @Test
    @DisplayName("options on a non-select field are refused")
    void optionsOnlyOnSelectFields() {
        UUID fieldId = UUID.randomUUID();
        ObjectNode field = data();
        field.put("name", "Wordcount").put("field_type", "number").put("order_key", "V");
        field.putArray("options").add("nope");

        PushResponse response = push(change("custom_metadata_field", fieldId, "create", null, field));
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("options"));
        assertThat(rows("custom_metadata_field", fieldId)).isZero();
    }

    // -------------------------------------------------------- compile presets

    @Test
    @DisplayName("a preset stores its format and its uuid array selection")
    void compilePresetLifecycle() {
        UUID one = binder.create(projectId, deviceA, null, "document", "One", null);
        UUID two = binder.create(projectId, deviceA, null, "document", "Two", one);

        UUID presetId = UUID.randomUUID();
        ObjectNode preset = data();
        preset.put("name", "Submission").put("format", "docx");
        preset.putArray("included_binder_items").add(one.toString()).add(two.toString());
        preset.putObject("separator_rules").put("betweenDocuments", "#");

        assertThat(push(change("compile_preset", presetId, "create", null, preset)).conflicts()).isEmpty();

        Map<String, Object> stored = row("compile_preset", presetId);
        assertThat(stored).containsEntry("format", "docx");
        Object array = stored.get("included_binder_items");
        assertThat(array).isNotNull();
        assertThat(jdbc.sql("SELECT array_length(included_binder_items, 1) FROM compile_preset WHERE id = :id")
                .param("id", presetId).query(Integer.class).single())
                .as("the JSON array must land as a real uuid[]")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a preset with no selection at all is refused")
    void presetNeedsASelection() {
        UUID presetId = UUID.randomUUID();
        ObjectNode preset = data();
        preset.put("name", "Empty").put("format", "epub");

        PushResponse response = push(change("compile_preset", presetId, "create", null, preset));
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("included_binder_items"));
        assertThat(rows("compile_preset", presetId)).isZero();
    }

    @Test
    @DisplayName("an unknown export format is refused")
    void unknownFormatRefused() {
        UUID presetId = UUID.randomUUID();
        ObjectNode preset = data();
        preset.put("name", "Weird").put("format", "wordperfect");
        preset.putObject("include_query").put("all", true);

        PushResponse response = push(change("compile_preset", presetId, "create", null, preset));
        assertThat(response.conflicts()).singleElement()
                .satisfies(c -> assertThat(c.detail()).contains("format"));
    }

    // ------------------------------------------------------------- behaviour

    @Test
    @DisplayName("every accepted change appends exactly one change_log row")
    void changesAreRecorded() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "label").put("name", "Blue").put("order_key", "V");
        push(change("taxonomy", id, "create", null, create));

        assertThat(jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE project_id = :p AND entity_type = 'taxonomy' AND entity_id = :id AND op = 'create'
                """).param("p", projectId).param("id", id).query(Long.class).single())
                .as("without this row no other device learns the label exists")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed change does not stop a valid one beside it")
    void malformedChangeDoesNotPoisonTheBatch() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        ObjectNode badData = data();
        badData.put("kind", "nonsense").put("name", "X").put("order_key", "V");
        ObjectNode goodData = data();
        goodData.put("kind", "label").put("name", "Fine").put("order_key", "k");

        PushResponse response = push(
                change("taxonomy", bad, "create", null, badData),
                change("taxonomy", good, "create", null, goodData));

        assertThat(response.conflicts()).hasSize(1);
        assertThat(response.applied()).hasSize(1);
        assertThat(rows("taxonomy", bad)).isZero();
        assertThat(rows("taxonomy", good)).isEqualTo(1);
    }

    @Test
    @DisplayName("deleting something already gone is accepted")
    void deleteIsIdempotent() {
        PushResponse response = push(change("collection", UUID.randomUUID(), "delete", null, null));
        assertThat(response.conflicts()).isEmpty();
        assertThat(response.applied()).hasSize(1);
    }

    @Test
    @DisplayName("the new types come back through pull, hydrated")
    void newTypesAppearInTheFeed() {
        UUID id = UUID.randomUUID();
        ObjectNode create = data();
        create.put("kind", "status").put("name", "First Draft").put("order_key", "V");
        push(change("taxonomy", id, "create", null, create));

        var pull = sync.pull(projectId, 0, 100);

        assertThat(pull.changes())
                .filteredOn(c -> "taxonomy".equals(c.entityType()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.entityId()).isEqualTo(id);
                    assertThat(c.data().get("name").asText()).isEqualTo("First Draft");
                    assertThat(c.data().get("kind").asText()).isEqualTo("status");
                });
    }
}

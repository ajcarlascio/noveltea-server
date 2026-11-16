package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.auth.AuthService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code label_id} and {@code status_id} on a pushed binder item.
 *
 * <p>The client sends a tag only when the author changed it, so the contract is: absent
 * means unchanged, explicit null clears, a value sets. A reference must name a live row
 * of the right kind in this project.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class SyncBinderTagsTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;

    private PushResponse push(ChangeRequest... changes) {
        return sync.push(projectId, deviceA, List.of(changes));
    }

    private UUID taxonomy(String kind, String name) {
        UUID id = UUID.randomUUID();
        ObjectNode d = mapper.createObjectNode();
        d.put("kind", kind).put("name", name).put("order_key", "V");
        assertThat(push(new ChangeRequest("taxonomy", id, "create", null, d)).conflicts()).isEmpty();
        return id;
    }

    private ObjectNode item(String title) {
        ObjectNode d = mapper.createObjectNode();
        d.put("type", "document").put("title", title).put("order_key", "V");
        return d;
    }

    private ChangeRequest create(UUID id, ObjectNode d) {
        return new ChangeRequest("binder_item", id, "create", null, d);
    }

    private ChangeRequest update(UUID id, long base, ObjectNode d) {
        return new ChangeRequest("binder_item", id, "update", base, d);
    }

    private UUID col(UUID id, String column) {
        return jdbc.sql("SELECT " + column + " FROM binder_item WHERE id = :id")
                .param("id", id).query(UUID.class).optional().orElse(null);
    }

    private long version(UUID id) {
        return jdbc.sql("SELECT version FROM binder_item WHERE id = :id")
                .param("id", id).query(Long.class).single();
    }

    @Test
    @DisplayName("a create stores its label and status")
    void createStoresTags() {
        UUID label = taxonomy("label", "POV");
        UUID status = taxonomy("status", "Draft");
        UUID id = UUID.randomUUID();
        ObjectNode d = item("One");
        d.put("label_id", label.toString()).put("status_id", status.toString());

        assertThat(push(create(id, d)).conflicts()).isEmpty();

        assertThat(col(id, "label_id")).isEqualTo(label);
        assertThat(col(id, "status_id")).isEqualTo(status);
    }

    @Test
    @DisplayName("an update sets a tag, leaves it alone when absent, and clears it on null")
    void updateContract() {
        UUID label = taxonomy("label", "POV");
        UUID status = taxonomy("status", "Draft");
        UUID id = UUID.randomUUID();
        push(create(id, item("One")));

        ObjectNode set = mapper.createObjectNode();
        set.put("label_id", label.toString()).put("status_id", status.toString());
        assertThat(push(update(id, 1, set)).conflicts()).isEmpty();
        assertThat(col(id, "label_id")).isEqualTo(label);
        assertThat(col(id, "status_id")).isEqualTo(status);

        ObjectNode rename = mapper.createObjectNode();
        rename.put("title", "Renamed");
        push(update(id, version(id), rename));
        assertThat(col(id, "label_id")).as("absent key is unchanged").isEqualTo(label);
        assertThat(col(id, "status_id")).isEqualTo(status);

        ObjectNode clear = mapper.createObjectNode();
        clear.putNull("label_id");
        push(update(id, version(id), clear));
        assertThat(col(id, "label_id")).as("explicit null clears").isNull();
        assertThat(col(id, "status_id")).as("the other tag is untouched").isEqualTo(status);
    }

    @Test
    @DisplayName("a tag change is on the change feed and bumps the version")
    void tagChangeIsFeedVisible() {
        UUID label = taxonomy("label", "POV");
        UUID id = UUID.randomUUID();
        push(create(id, item("One")));
        long cursor = sync.pull(projectId, deviceB, 0, 100, null).latestId();

        ObjectNode set = mapper.createObjectNode();
        set.put("label_id", label.toString());
        push(update(id, 1, set));

        var pulled = sync.pull(projectId, deviceB, cursor, 100, null);
        assertThat(pulled.changes()).anySatisfy(c -> {
            assertThat(c.entityId()).isEqualTo(id);
            assertThat(c.data().get("label_id").asText()).isEqualTo(label.toString());
        });
        assertThat(version(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("an update that arrives as a create carries its tags too")
    void upsertPathStoresTags() {
        UUID label = taxonomy("label", "POV");
        UUID id = UUID.randomUUID();
        ObjectNode d = item("One");
        d.put("label_id", label.toString());

        assertThat(push(update(id, 1, d)).conflicts()).isEmpty();
        assertThat(col(id, "label_id")).isEqualTo(label);
    }

    @Test
    @DisplayName("a label and an item in one push, label first, works")
    void sameBatchOrdering() {
        UUID label = UUID.randomUUID();
        ObjectNode t = mapper.createObjectNode();
        t.put("kind", "label").put("name", "POV").put("order_key", "V");
        UUID id = UUID.randomUUID();
        ObjectNode d = item("One");
        d.put("label_id", label.toString());

        PushResponse r = push(new ChangeRequest("taxonomy", label, "create", null, t), create(id, d));

        assertThat(r.conflicts()).isEmpty();
        assertThat(col(id, "label_id")).isEqualTo(label);
    }

    @Test
    @DisplayName("a wrong-kind, foreign-project, tombstoned or unknown reference is refused whole")
    void badReferencesAreRefused() {
        UUID status = taxonomy("status", "Draft");
        UUID label = taxonomy("label", "POV");
        UUID gone = taxonomy("label", "Old");
        jdbc.sql("UPDATE taxonomy SET deleted_at = now() WHERE id = :id").param("id", gone).update();

        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        jdbc.sql("INSERT INTO taxonomy (id, project_id, kind, name, order_key) "
                        + "VALUES (:id, :p, 'label', 'X', 'V')")
                .param("id", foreign).param("p", otherProject).update();

        for (UUID bad : List.of(status, gone, foreign, UUID.randomUUID())) {
            UUID id = UUID.randomUUID();
            ObjectNode first = item("One");
            first.put("order_key", "K" + bad); // order_key is unique per parent
            push(create(id, first));
            ObjectNode d = mapper.createObjectNode();
            d.put("title", "Renamed").put("label_id", bad.toString());

            PushResponse r = push(update(id, 1, d));

            assertThat(r.conflicts()).hasSize(1);
            assertThat(r.conflicts().get(0).reason()).isEqualTo(ConflictReason.INVALID_REQUEST);
            assertThat(col(id, "label_id")).isNull();
            assertThat(jdbc.sql("SELECT title FROM binder_item WHERE id = :id")
                            .param("id", id).query(String.class).single())
                    .as("the whole change is refused, as for a bad parent_id").isEqualTo("One");
        }
        assertThat(label).isNotNull();

        // The same reference on a create must not insert the row.
        UUID id = UUID.randomUUID();
        ObjectNode d = item("Two");
        d.put("status_id", label.toString());
        assertThat(push(create(id, d)).conflicts()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM binder_item WHERE id = :id")
                        .param("id", id).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("over HTTP: a pushed label comes back from /binder, and its row from /sync/state")
    void throughHttp() throws Exception {
        var session = auth.register("tags-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "Laptop", "web");
        UUID project = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Mine')")
                .param("id", project).param("o", session.userId()).update();
        UUID label = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String body = """
                {"changes":[
                 {"entityType":"taxonomy","entityId":"%s","op":"create","data":{"kind":"label","name":"POV","order_key":"V"}},
                 {"entityType":"binder_item","entityId":"%s","op":"create","data":{"type":"document","title":"One","order_key":"V","label_id":"%s"}}
                ]}""".formatted(label, id, label);

        JsonNode pushed = mapper.readTree(mvc.perform(post("/api/v1/projects/{id}/sync", project)
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertThat(pushed.get("conflicts")).isEmpty();

        JsonNode binder = mapper.readTree(mvc.perform(get("/api/v1/projects/{id}/binder", project)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn().getResponse().getContentAsString());
        assertThat(binder.toString()).contains("\"labelId\":\"" + label + "\"");

        JsonNode state = mapper.readTree(mvc.perform(get("/api/v1/projects/{id}/sync/state", project)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andReturn().getResponse().getContentAsString());
        // /sync/state carries the taxonomy the item points at; the item itself is in /binder.
        assertThat(state.toString()).contains("\"entityId\":\"" + label + "\"");
    }
}

package com.noveltea.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.binder.BinderService;
import com.noveltea.comment.CommentService;
import com.noveltea.support.AbstractPostgresTest;
import com.noveltea.sync.dto.SyncDtos.ChangeRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Thread integrity for comments arriving over sync.
 *
 * <p>The REST path refuses a reply whose parent belongs to another document. A reply
 * arriving over sync is the same operation and must obey the same rule — otherwise a
 * client can attach a reply to a thread it was never shown, including one in another
 * account, where it appears as though somebody joined a private conversation.
 */
class SyncCommentThreadTest extends AbstractPostgresTest {

    @Autowired SyncService sync;
    @Autowired CommentService comments;
    @Autowired BinderService binder;
    @Autowired ObjectMapper mapper;

    private ChangeRequest reply(UUID id, UUID documentId, UUID parentId, String body) {
        ObjectNode data = mapper.createObjectNode();
        data.put("document_id", documentId.toString()).put("body", body);
        if (parentId != null) {
            data.put("parent_comment_id", parentId.toString());
        }
        return new ChangeRequest("comment", id, "create", null, data);
    }

    private ChangeRequest resolve(UUID id, boolean resolved) {
        ObjectNode data = mapper.createObjectNode();
        data.put("resolved", resolved);
        return new ChangeRequest("comment", id, "update", null, data);
    }

    private boolean isResolved(UUID id) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT resolved_at IS NOT NULL FROM comment WHERE id = :id")
                .param("id", id).query(Boolean.class).single());
    }

    /** author_user_id has a foreign key, so somebody else has to actually exist. */
    private UUID someoneElse() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", id).param("e", id + "@example.com").update();
        return id;
    }

    private long repliesTo(UUID parentId) {
        return jdbc.sql("SELECT count(*) FROM comment WHERE parent_comment_id = :id")
                .param("id", parentId).query(Long.class).single();
    }

    @Test
    @DisplayName("A REPLY CANNOT ATTACH TO A THREAD IN ANOTHER ACCOUNT")
    void cannotReplyIntoAnotherProjectsThread() {
        UUID otherUser = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :e)")
                .param("id", otherUser).param("e", otherUser + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Theirs')")
                .param("id", otherProject).param("o", otherUser).update();
        UUID theirDoc = binder.create(otherProject, deviceB, null, "document", "Their Chapter", null);
        jdbc.sql("INSERT INTO document (id) VALUES (:id)").param("id", theirDoc).update();
        UUID theirThread = comments.create(theirDoc, otherUser, deviceB, "their private note", null, null);

        UUID myDoc = seedDocument("My Chapter", "V", "my prose");
        PushResponse response = sync.push(projectId, deviceA,
                List.of(reply(UUID.randomUUID(), myDoc, theirThread, "intruding")));

        assertThat(repliesTo(theirThread))
                .as("a stranger's reply must not appear inside a private thread")
                .isZero();
        assertThat(response.conflicts()).hasSize(1);
    }

    @Test
    @DisplayName("a reply cannot attach to a thread on a different document")
    void cannotReplyAcrossDocuments() {
        UUID first = seedDocument("Chapter One", "V", "prose one");
        UUID second = seedDocument("Chapter Two", "k", "prose two");
        UUID thread = comments.create(first, userId, deviceA, "a note", null, null);

        PushResponse response = sync.push(projectId, deviceA,
                List.of(reply(UUID.randomUUID(), second, thread, "wrong document")));

        assertThat(repliesTo(thread))
                .as("a thread pointing at two documents is not a thread")
                .isZero();
        assertThat(response.conflicts()).hasSize(1);
    }

    @Test
    @DisplayName("a reply to a thread that does not exist is refused")
    void cannotReplyToNothing() {
        UUID docId = seedDocument("Chapter One", "V", "prose");

        PushResponse response = sync.push(projectId, deviceA,
                List.of(reply(UUID.randomUUID(), docId, UUID.randomUUID(), "into the void")));

        assertThat(response.conflicts()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM comment").query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a legitimate reply over sync still works and inherits the thread's anchor")
    void legitimateReplyWorks() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        ObjectNode anchor = mapper.createObjectNode();
        anchor.put("from", 1).put("to", 5).put("quotedText", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "is this right?", anchor, null);

        UUID replyId = UUID.randomUUID();
        PushResponse response = sync.push(projectId, deviceA,
                List.of(reply(replyId, docId, thread, "I think so")));

        assertThat(response.conflicts()).isEmpty();
        assertThat(repliesTo(thread)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT anchor FROM comment WHERE id = :id")
                .param("id", replyId).query(String.class).optional())
                .as("a reply inherits its thread's anchor rather than carrying its own")
                .isEmpty();
    }

    // ------------------------------------------------------------- resolving

    @Test
    @DisplayName("A THREAD RESOLVED OFFLINE STILL CLOSES WHEN THE DEVICE RECONNECTS")
    void resolveTravelsOverSync() {
        // The push path once carried only `body` on a comment update, so a thread closed
        // on a phone with no signal stayed open everywhere else, for ever.
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "is this right?", null, null);

        PushResponse response = sync.push(projectId, deviceA, List.of(resolve(thread, true)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(isResolved(thread)).isTrue();
    }

    @Test
    @DisplayName("reopening travels too")
    void reopenTravelsOverSync() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "is this right?", null, null);
        comments.setResolved(thread, userId, true, deviceA);

        sync.push(projectId, deviceA, List.of(resolve(thread, false)));

        assertThat(isResolved(thread)).isFalse();
    }

    @Test
    @DisplayName("an ordinary body edit does not reopen a resolved thread")
    void bodyEditLeavesResolutionAlone() {
        // A missing `resolved` field means "leave it alone". Read as false, every edit
        // anywhere would quietly reopen threads someone had already dealt with.
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "is this right?", null, null);
        comments.setResolved(thread, userId, true, deviceA);

        ObjectNode data = mapper.createObjectNode();
        data.put("body", "is this right, really?");
        sync.push(projectId, deviceA, List.of(new ChangeRequest("comment", thread, "update", null, data)));

        assertThat(isResolved(thread)).isTrue();
        assertThat(jdbc.sql("SELECT body FROM comment WHERE id = :id")
                .param("id", thread).query(String.class).single())
                .isEqualTo("is this right, really?");
    }

    @Test
    @DisplayName("an update carrying neither body nor resolved does not reopen anything")
    void anEmptyUpdateIsNotAReopen() {
        // "resolved" absent means leave it alone. Were it read as false, an update that
        // named neither field — a client sending an anchor change, or a payload this
        // version does not know about — would quietly reopen a thread somebody closed.
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "a note", null, null);
        comments.setResolved(thread, userId, true, deviceA);

        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest("comment", thread, "update", null, mapper.createObjectNode())));

        assertThat(isResolved(thread)).isTrue();
        assertThat(response.conflicts())
                .as("an update that changes nothing it understands is refused, not guessed at")
                .hasSize(1);
    }

    @Test
    @DisplayName("anyone who can write to the project may resolve, not only the author")
    void resolvingIsNotEditing() {
        // Editing and deleting stay with the author; resolving is agreeing that a note
        // has been dealt with, which is the collaborator's half of the conversation.
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "a note", null, null);
        jdbc.sql("UPDATE comment SET author_user_id = :other WHERE id = :id")
                .param("other", someoneElse()).param("id", thread).update();

        PushResponse response = sync.push(projectId, deviceA, List.of(resolve(thread, true)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(isResolved(thread)).isTrue();
    }

    @Test
    @DisplayName("A FULL-STATE PAYLOAD CAN RESOLVE A COMMENT SOMEBODY ELSE WROTE")
    void fullStatePayloadResolvesWithoutLookingLikeAnEdit() {
        // The client's outbox coalesces two updates to one entity by replacing the
        // payload, so it sends the comment's whole state — body included — or an edit
        // followed by a resolve loses the edit. An unchanged body must therefore not
        // count as editing, or nobody could ever close a thread they had not written.
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "a note", null, null);
        jdbc.sql("UPDATE comment SET author_user_id = :other WHERE id = :id")
                .param("other", someoneElse()).param("id", thread).update();

        ObjectNode data = mapper.createObjectNode();
        data.put("body", "a note").put("resolved", true);
        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest("comment", thread, "update", null, data)));

        assertThat(response.conflicts()).isEmpty();
        assertThat(isResolved(thread)).isTrue();
    }

    @Test
    @DisplayName("one payload carrying both an edit and a resolve applies both")
    void bodyAndResolutionTravelTogether() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "a note", null, null);

        ObjectNode data = mapper.createObjectNode();
        data.put("body", "a better note").put("resolved", true);
        sync.push(projectId, deviceA,
                List.of(new ChangeRequest("comment", thread, "update", null, data)));

        assertThat(isResolved(thread)).isTrue();
        assertThat(jdbc.sql("SELECT body FROM comment WHERE id = :id")
                .param("id", thread).query(String.class).single()).isEqualTo("a better note");
    }

    @Test
    @DisplayName("editing someone else's comment is still refused")
    void editingIsStillTheAuthorsAlone() {
        UUID docId = seedDocument("Chapter One", "V", "prose");
        UUID thread = comments.create(docId, userId, deviceA, "a note", null, null);
        jdbc.sql("UPDATE comment SET author_user_id = :other WHERE id = :id")
                .param("other", someoneElse()).param("id", thread).update();

        ObjectNode data = mapper.createObjectNode();
        data.put("body", "putting words in their mouth");
        PushResponse response = sync.push(projectId, deviceA,
                List.of(new ChangeRequest("comment", thread, "update", null, data)));

        assertThat(response.conflicts()).hasSize(1);
        assertThat(jdbc.sql("SELECT body FROM comment WHERE id = :id")
                .param("id", thread).query(String.class).single()).isEqualTo("a note");
    }
}

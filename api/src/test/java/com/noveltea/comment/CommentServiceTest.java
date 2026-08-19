package com.noveltea.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.noveltea.auth.AuthService;
import com.noveltea.comment.CommentExceptions.CommentNotFound;
import com.noveltea.comment.CommentExceptions.NotTheAuthor;
import com.noveltea.comment.CommentService.Comment;
import com.noveltea.mail.Mailer;
import com.noveltea.support.AbstractPostgresTest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class CommentServiceTest extends AbstractPostgresTest {

    @Autowired CommentService comments;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;
    @Autowired RecordingMailer mailer;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingMailer recordingMailer() {
            return new RecordingMailer();
        }
    }

    static class RecordingMailer implements Mailer {
        final List<String[]> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(String to, String subject, String body) {
            if (body != null && body.contains("BOOM")) {
                throw new IllegalStateException("simulated SMTP failure");
            }
            sent.add(new String[] {to, subject, body});
        }

        @Override
        public boolean isDelivering() {
            return true;
        }

        List<String[]> to(String address) {
            return sent.stream().filter(m -> m[0].equals(address)).toList();
        }
    }

    private ObjectNode anchor(String quoted) {
        ObjectNode node = mapper.createObjectNode();
        node.put("from", 10).put("to", 30).put("quotedText", quoted);
        return node;
    }

    private UUID documentWithText(String text) {
        UUID id = seedDocument("Chapter One", "V", text);
        jdbc.sql("UPDATE document SET search_text = :t WHERE id = :id")
                .param("t", text).param("id", id).update();
        return id;
    }

    // --------------------------------------------------------------- basics

    @Test
    @DisplayName("a comment is stored against its document with the caller as author")
    void createsComment() {
        UUID docId = documentWithText("the lighthouse keeper waited");
        UUID commentId = comments.create(docId, userId, deviceA, "  is this too slow?  ", null, null);

        List<Comment> all = comments.forDocument(docId);
        assertThat(all).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(commentId);
            assertThat(c.body()).isEqualTo("is this too slow?");
            assertThat(c.authorUserId()).isEqualTo(userId);
            assertThat(c.resolvedAt()).isNull();
        });
    }

    @Test
    @DisplayName("an empty comment is refused")
    void emptyCommentRefused() {
        UUID docId = documentWithText("prose");
        for (String body : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> comments.create(docId, userId, deviceA, body, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(comments.forDocument(docId)).isEmpty();
    }

    @Test
    @DisplayName("a comment on an unknown document is refused")
    void unknownDocumentRefused() {
        assertThatThrownBy(() -> comments.create(UUID.randomUUID(), userId, deviceA, "x", null, null))
                .isInstanceOf(CommentNotFound.class);
    }

    @Test
    @DisplayName("every comment appends a feed row so other devices see it")
    void commentsSync() {
        UUID docId = documentWithText("prose");
        UUID commentId = comments.create(docId, userId, deviceA, "a note", null, null);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM change_log
                 WHERE entity_type = 'comment' AND entity_id = :id AND op = 'create'
                """).param("id", commentId).query(Long.class).single()).isEqualTo(1);
    }

    // -------------------------------------------------------------- anchors

    @Test
    @DisplayName("an anchored comment is not orphaned while its quoted text is present")
    void anchorHoldsWhileTextRemains() {
        UUID docId = documentWithText("the lighthouse keeper waited for morning");
        comments.create(docId, userId, deviceA, "check this", anchor("lighthouse keeper"), null);

        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.orphaned()).isFalse());
    }

    @Test
    @DisplayName("AN ANCHOR WHOSE TEXT IS GONE IS ORPHANED, NOT DELETED OR MOVED")
    void anchorBecomesOrphanedNotLost() {
        UUID docId = documentWithText("the lighthouse keeper waited for morning");
        comments.create(docId, userId, deviceA, "check this", anchor("lighthouse keeper"), null);

        jdbc.sql("UPDATE document SET search_text = 'an entirely rewritten scene' WHERE id = :id")
                .param("id", docId).update();

        assertThat(comments.forDocument(docId)).singleElement().satisfies(c -> {
            assertThat(c.orphaned())
                    .as("silently relocating a note to the wrong sentence is worse than saying it lost its place")
                    .isTrue();
            assertThat(c.body()).isEqualTo("check this");
        });
    }

    @Test
    @DisplayName("an anchor survives text moving, because it matches on words not offsets")
    void anchorSurvivesReflow() {
        UUID docId = documentWithText("the lighthouse keeper waited");
        comments.create(docId, userId, deviceA, "note", anchor("lighthouse keeper"), null);

        jdbc.sql("UPDATE document SET search_text = :t WHERE id = :id")
                .param("t", "a long new opening paragraph, and then the lighthouse keeper waited")
                .param("id", docId).update();

        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.orphaned())
                        .as("offsets drift with every edit; the words usually survive")
                        .isFalse());
    }

    @Test
    @DisplayName("a comment with no anchor is never orphaned")
    void unanchoredCommentsAreNeverOrphaned() {
        UUID docId = documentWithText("prose");
        comments.create(docId, userId, deviceA, "general thought", null, null);
        jdbc.sql("UPDATE document SET search_text = 'completely different' WHERE id = :id")
                .param("id", docId).update();

        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.orphaned()).isFalse());
    }

    // --------------------------------------------------------------- threads

    @Test
    @DisplayName("replies attach to their thread and carry no anchor of their own")
    void repliesJoinTheThread() {
        UUID docId = documentWithText("the lighthouse keeper waited");
        UUID root = comments.create(docId, userId, deviceA, "too slow?", anchor("lighthouse"), null);
        UUID reply = comments.create(docId, userId, deviceA, "agreed", anchor("keeper"), root);

        List<Comment> all = comments.forDocument(docId);
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(c -> c.id().equals(reply)).findFirst()).hasValueSatisfying(c -> {
            assertThat(c.parentCommentId()).isEqualTo(root);
            assertThat(c.anchor())
                    .as("a thread pointing at two places at once is not a thread")
                    .isNull();
        });
    }

    @Test
    @DisplayName("a reply cannot cross to another document")
    void repliesStayOnTheirDocument() {
        UUID first = documentWithText("one");
        UUID second = seedDocument("Chapter Two", "k", "two");
        UUID root = comments.create(first, userId, deviceA, "note", null, null);

        assertThatThrownBy(() -> comments.create(second, userId, deviceA, "reply", null, root))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------ authorship

    @Test
    @DisplayName("only the author may edit or delete a comment")
    void onlyAuthorMayChange() {
        UUID docId = documentWithText("prose");
        UUID commentId = comments.create(docId, userId, deviceA, "mine", null, null);
        UUID stranger = auth.register("other-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "x", "web").userId();

        assertThatThrownBy(() -> comments.edit(commentId, stranger, "rewritten", deviceA))
                .isInstanceOf(NotTheAuthor.class);
        assertThatThrownBy(() -> comments.delete(commentId, stranger, deviceA))
                .isInstanceOf(NotTheAuthor.class);
        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.body()).isEqualTo("mine"));
    }

    @Test
    @DisplayName("anyone with write access may resolve — that is a shared editorial act")
    void anyoneMayResolve() {
        UUID docId = documentWithText("prose");
        UUID commentId = comments.create(docId, userId, deviceA, "question", null, null);
        UUID other = auth.register("res-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "x", "web").userId();

        comments.setResolved(commentId, other, true, deviceA);
        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.resolvedAt()).isNotNull());

        comments.setResolved(commentId, other, false, deviceA);
        assertThat(comments.forDocument(docId)).singleElement()
                .satisfies(c -> assertThat(c.resolvedAt()).isNull());
    }

    @Test
    @DisplayName("deleting is soft, so other devices learn about it")
    void deleteIsSoftAndSynced() {
        UUID docId = documentWithText("prose");
        UUID commentId = comments.create(docId, userId, deviceA, "note", null, null);

        comments.delete(commentId, userId, deviceA);

        assertThat(comments.forDocument(docId)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE id = :id")
                .param("id", commentId).query(Long.class).single())
                .as("the row survives so the deletion can propagate")
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM change_log WHERE entity_id = :id AND op = 'delete'
                """).param("id", commentId).query(Long.class).single()).isEqualTo(1);
    }

    // ---------------------------------------------------------- notification

    @Test
    @DisplayName("the project owner is emailed when someone else comments")
    void ownerIsNotified() {
        String ownerEmail = jdbc.sql("SELECT email FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
        UUID docId = documentWithText("prose");
        UUID commenter = auth.register("commenter-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "x", "web").userId();

        comments.create(docId, commenter, deviceA, "this scene drags", null, null);

        assertThat(mailer.to(ownerEmail)).singleElement().satisfies(message -> {
            assertThat(message[1]).contains("Chapter One");
            assertThat(message[2]).contains("this scene drags");
        });
    }

    @Test
    @DisplayName("nobody is emailed about their own comment")
    void noSelfNotification() {
        String ownerEmail = jdbc.sql("SELECT email FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).single();
        UUID docId = documentWithText("prose");

        comments.create(docId, userId, deviceA, "note to self", null, null);

        assertThat(mailer.to(ownerEmail))
                .as("talking to yourself in the margins is normal; mailing yourself about it is not")
                .isEmpty();
    }

    @Test
    @DisplayName("a comment is saved even when sending the notification throws")
    void notificationFailureDoesNotLoseTheComment() {
        UUID docId = documentWithText("prose");
        UUID commenter = auth.register("boom-" + UUID.randomUUID() + "@example.com",
                "correct horse battery staple", "x", "web").userId();

        // RecordingMailer throws on this marker, standing in for an unreachable SMTP server.
        UUID commentId = comments.create(docId, commenter, deviceA, "BOOM still saved", null, null);

        assertThat(jdbc.sql("SELECT count(*) FROM comment WHERE id = :id")
                .param("id", commentId).query(Long.class).single())
                .as("an editor's remark must not depend on a mail server being reachable")
                .isEqualTo(1);
    }
}

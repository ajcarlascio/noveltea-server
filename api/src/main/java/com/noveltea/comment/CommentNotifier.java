package com.noveltea.comment;

import com.noveltea.mail.MailProperties;
import com.noveltea.mail.Mailer;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Tells people a comment was left.
 *
 * <p>Notification is the reason comments and mail landed together: a note nobody hears
 * about is a note nobody answers.
 *
 * <p>Nothing here may fail the comment. Saving an editor's remark must not depend on a
 * mail server being reachable, so every failure is logged and swallowed.
 */
@Component
public class CommentNotifier {

    private static final Logger log = LoggerFactory.getLogger(CommentNotifier.class);
    private static final int EXCERPT_LENGTH = 300;

    private final JdbcClient jdbc;
    private final Mailer mailer;
    private final MailProperties properties;

    // email is a citext column, so every read of it casts to text: the driver returns
    // citext as a PGobject, and a hard cast to String throws inside a swallowed catch —
    // which looks exactly like "notifications silently do not work".
    public CommentNotifier(JdbcClient jdbc, Mailer mailer, MailProperties properties) {
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.properties = properties;
    }

    /** Notifies the project owner, unless they are the one who wrote it. */
    public void commentAdded(
            UUID projectId, UUID documentId, UUID commentId, UUID authorUserId, String body) {
        try {
            Map<String, Object> context = jdbc.sql("""
                    SELECT owner.id AS owner_id, owner.email::text AS owner_email,
                           p.title AS project_title, b.title AS document_title,
                           author.email::text AS author_email
                      FROM project p
                      JOIN app_user owner ON owner.id = p.owner_id
                      JOIN binder_item b ON b.id = :documentId
                      LEFT JOIN app_user author ON author.id = :authorId
                     WHERE p.id = :projectId
                    """)
                    .param("documentId", documentId)
                    .param("authorId", authorUserId)
                    .param("projectId", projectId)
                    .query()
                    .listOfRows()
                    .stream()
                    .findFirst()
                    .orElse(null);

            if (context == null) {
                return;
            }
            UUID ownerId = (UUID) context.get("owner_id");
            if (ownerId.equals(authorUserId)) {
                // Talking to yourself in the margins is normal; mailing yourself about it is not.
                return;
            }

            String who = (String) context.get("author_email");
            mailer.send(
                    (String) context.get("owner_email"),
                    "New comment on \"%s\"".formatted(context.get("document_title")),
                    """
                    %s left a comment on "%s" in %s:

                    %s
                    %s
                    """.formatted(
                            who == null ? "Someone" : who,
                            context.get("document_title"),
                            context.get("project_title"),
                            excerpt(body),
                            link(projectId, documentId)));
        } catch (Exception e) {
            log.warn("could not notify about comment {}: {}", commentId, e.getMessage());
        }
    }

    private static String excerpt(String body) {
        String trimmed = body.strip();
        return trimmed.length() <= EXCERPT_LENGTH ? trimmed : trimmed.substring(0, EXCERPT_LENGTH) + "…";
    }

    private String link(UUID projectId, UUID documentId) {
        return properties.appUrl() == null
                ? ""
                : "\n%s/projects/%s/documents/%s".formatted(properties.appUrl(), projectId, documentId);
    }
}

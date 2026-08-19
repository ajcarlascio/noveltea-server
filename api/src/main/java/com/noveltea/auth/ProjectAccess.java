package com.noveltea.auth;

import com.noveltea.auth.AuthExceptions.AccessDenied;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The single place that decides whether a caller may touch a project.
 *
 * <p>Core recognises one relationship: ownership. Membership and subtree scoping arrive
 * with the commercial `SharingProvider`, which extends this check rather than replacing
 * it — Core must keep working with no provider present.
 *
 * <p>A project the caller cannot see is reported as absent, not forbidden. Distinguishing
 * the two confirms the project exists to someone who was never granted it.
 */
@Service
public class ProjectAccess {

    private final JdbcClient jdbc;

    public ProjectAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requireReadable(CurrentUser user, UUID projectId) {
        requireOwner(user, projectId, false);
    }

    public void requireWritable(CurrentUser user, UUID projectId) {
        requireOwner(user, projectId, false);
    }

    /**
     * For restore and purge, which by definition act on a project that is already hidden.
     * Everything else must use the checks above, so a deleted project stays invisible.
     */
    public void requireOwnerIncludingDeleted(CurrentUser user, UUID projectId) {
        requireOwner(user, projectId, true);
    }

    /** Resolves the project owning a binder item, then checks access against it. */
    public UUID requireWritableItem(CurrentUser user, UUID binderItemId) {
        UUID projectId = jdbc
                .sql("SELECT project_id FROM binder_item WHERE id = :id")
                .param("id", binderItemId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new AccessDenied("no such item"));
        requireOwner(user, projectId, false);
        return projectId;
    }

    private void requireOwner(CurrentUser user, UUID projectId, boolean includeDeleted) {
        if (user == null || user.userId() == null) {
            throw new AccessDenied("not authenticated");
        }
        boolean owns = Boolean.TRUE.equals(jdbc
                .sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM project
                             WHERE id = :id AND owner_id = :userId
                               AND (:includeDeleted OR deleted_at IS NULL))
                        """)
                .param("id", projectId)
                .param("userId", user.userId())
                .param("includeDeleted", includeDeleted)
                .query(Boolean.class)
                .single());
        if (!owns) {
            throw new AccessDenied("no such project");
        }
    }
}

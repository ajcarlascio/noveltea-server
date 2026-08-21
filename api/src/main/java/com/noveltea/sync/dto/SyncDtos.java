package com.noveltea.sync.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SyncDtos {
    private SyncDtos() {}

    /** One row of the server's change feed, with the entity's current state attached. */
    public record ChangeRecord(
            long id,
            String entityType,
            UUID entityId,
            String op,
            UUID deviceId,
            OffsetDateTime createdAt,
            JsonNode data) {}

    /**
     * @param latestId the highest change id ACTUALLY SERVED in this response — never
     *     the table maximum. A client that advanced past unserved rows would skip them
     *     permanently.
     */
    /**
     * @param resyncRequired the client's cursor points into history that has been purged.
     *     It must rebuild from the current state (binder + documents) and resume at
     *     {@code latestId}, because the feed can no longer explain how things got here.
     */
    public record PullResponse(
            List<ChangeRecord> changes,
            @Schema(
                            description =
                                    "Highest change id actually served in this response — "
                                            + "never the feed's true maximum. A client that "
                                            + "advanced past unserved rows would skip them "
                                            + "permanently.")
                    long latestId,
            @Schema(description = "More rows exist past this page; pull again from latestId.")
                    boolean hasMore,
            @Schema(
                            description =
                                    "The client's cursor points into history the server can no "
                                            + "longer explain (purged by retention, or the "
                                            + "project was restored from an older backup). "
                                            + "`changes` is empty; the client must rebuild from "
                                            + "GET /binder plus documents and resume at "
                                            + "latestId.")
                    boolean resyncRequired,
            long syncEpoch) {

        public PullResponse(List<ChangeRecord> changes, long latestId, boolean hasMore) {
            this(changes, latestId, hasMore, false, 1L);
        }
    }

    public record ChangeRequest(
            String entityType, UUID entityId, String op, Long baseVersion, JsonNode data) {}

    public record PushRequest(Long since, List<ChangeRequest> changes) {}

    public record AppliedChange(UUID entityId, String entityType, long version) {}

    /**
     * @param conflictCopyId the binder_item holding the client's rejected version, when
     *     one was created. Non-null means the author's text was preserved elsewhere and
     *     needs manual reconciliation; null means nothing was at risk.
     */
    public record ConflictRecord(
            UUID entityId,
            String entityType,
            @Schema(
                            description =
                                    "One of ConflictReason: version_mismatch (stale "
                                            + "baseVersion — see conflictCopyId), "
                                            + "duplicate_create, entity_missing, "
                                            + "invalid_request, or not_implemented for an "
                                            + "entity type this push path does not yet handle.",
                            example = "version_mismatch")
                    String reason,
            @Schema(
                            description =
                                    "The binder_item holding the client's rejected document "
                                            + "version, when one was created. Non-null means the "
                                            + "author's text was preserved elsewhere and needs "
                                            + "manual reconciliation via the Conflicts "
                                            + "endpoints; null means nothing was at risk (e.g. a "
                                            + "tree write, which is last-write-wins).")
                    UUID conflictCopyId,
            Long serverVersion,
            String detail) {

        public ConflictRecord(
                UUID entityId, String entityType, String reason, UUID conflictCopyId, Long serverVersion) {
            this(entityId, entityType, reason, conflictCopyId, serverVersion, null);
        }
    }

    public record PushResponse(
            List<AppliedChange> applied, List<ConflictRecord> conflicts, long latestId) {}
}

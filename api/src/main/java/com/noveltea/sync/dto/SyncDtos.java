package com.noveltea.sync.dto;

import com.fasterxml.jackson.databind.JsonNode;
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
    public record PullResponse(List<ChangeRecord> changes, long latestId, boolean hasMore) {}

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
            UUID entityId, String entityType, String reason, UUID conflictCopyId, Long serverVersion) {}

    public record PushResponse(
            List<AppliedChange> applied, List<ConflictRecord> conflicts, long latestId) {}
}

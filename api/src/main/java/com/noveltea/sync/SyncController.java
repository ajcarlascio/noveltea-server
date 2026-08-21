package com.noveltea.sync;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.sync.dto.SyncDtos.PullResponse;
import com.noveltea.sync.dto.SyncDtos.PushRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sync")
@Tag(
        name = "Sync",
        description = "The offline sync protocol. Clients pull an append-only change feed and "
                + "push local changes; see each operation for how conflicts and resync are "
                + "reported — both are ordinary outcomes, not errors.")
public class SyncController {

    private final SyncService sync;
    private final ProjectAccess access;

    public SyncController(SyncService sync, ProjectAccess access) {
        this.sync = sync;
        this.access = access;
    }

    @Operation(
            summary = "Pull the change feed since a cursor",
            description = """
                    Returns rows appended since `since` (exclusive), each carrying the \
                    entity's current state. Always answers 200; the response body, not the \
                    status code, carries the two conditions a client must check:

                    - **`resyncRequired: true`** — the cursor points into history the server \
                    can no longer explain, either because it was purged by retention or \
                    because the project was restored from an older backup (`syncEpoch` \
                    changed). `changes` is empty; the client must discard its cursor, rebuild \
                    from `GET /binder` plus documents, and resume pulling from `latestId`.
                    - **`hasMore: true`** — more rows exist past this page; pull again from \
                    `latestId` before considering the client caught up.

                    `latestId` is the highest id *actually served*, never the feed's true \
                    maximum — advancing past unserved rows would skip them permanently.""")
    @GetMapping
    public PullResponse pull(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(required = false) Long epoch) {
        access.requireReadable(user, projectId);
        return sync.pull(projectId, user.deviceId(), since, limit, epoch);
    }

    /**
     * The device is taken from the access token, never from a header — a client cannot
     * attribute its writes to somebody else's device.
     */
    @Operation(
            summary = "Push local changes",
            description = """
                    Applies each change independently and always answers 200 — a per-change \
                    conflict is a normal, expected outcome of concurrent editing, not a \
                    request-level failure. The response separates `applied` from `conflicts` \
                    so a client can retry only what actually failed.

                    A `document` write that loses a conflict is never merged and never \
                    dropped: the server keeps its own version, and the client's is preserved \
                    as a sibling "conflict copy" binder item, whose id comes back as \
                    `conflictCopyId` on the `ConflictRecord` — non-null there means the \
                    author's text survived elsewhere and needs manual reconciliation via the \
                    Conflicts endpoints. Tree writes (`binder_item`) are last-write-wins \
                    instead; only document content gets a conflict copy.

                    See `ConflictReason` for the `reason` values: `version_mismatch` (stale \
                    `baseVersion` — the conflict-copy case above), `duplicate_create`, \
                    `entity_missing`, `invalid_request`, and `not_implemented` for entity \
                    types this push path does not yet handle.""")
    @PostMapping
    public PushResponse push(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody PushRequest request) {
        access.requireWritable(user, projectId);
        return sync.push(projectId, user.deviceId(), request.changes());
    }
}

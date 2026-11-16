package com.noveltea.sync;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.sync.dto.SyncDtos.PullResponse;
import com.noveltea.sync.dto.SyncDtos.PushRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
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
    private final ProjectStateService state;
    private final ProjectAccess access;

    public SyncController(SyncService sync, ProjectStateService state, ProjectAccess access) {
        this.sync = sync;
        this.state = state;
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
                    from `GET /binder`, documents and `GET /sync/state`, and resume pulling \
                    from `latestId`.
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

    @Operation(
            summary = "Read every other synced entity, for a rebuild",
            description = """
                    The current rows of each synced type that `GET /binder` and \
                    `GET /documents` do not cover: taxonomy, custom metadata fields and values, \
                    collections and their items, compile presets, comments, and manual \
                    snapshots without their content. This is what a client uses after \
                    `GET /sync` answers `resyncRequired`, for the same reason documents \
                    exist: once retention has trimmed the feed, nothing else describes them.

                    Each row is `{entityType, entityId, data}`, with `data` exactly as a feed \
                    row carries it. Rows come in insertion order — anything a row references \
                    precedes it — except binder items, which the values, collection items, \
                    comments and snapshots at the end need first.

                    Tombstoned rows are included, so nothing that references one is left \
                    dangling; rows hanging off a tombstoned binder item are not. All of it is \
                    read from one snapshot. Fetched after the resync answer, it reflects at \
                    least the state at that answer's `latestId`, so resuming the feed there \
                    leaves no gap.""")
    @GetMapping("/state")
    public List<ProjectStateService.EntityRow> state(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);
        return state.state(projectId);
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

                    A `binder_item` change may carry `label_id` and `status_id`: absent \
                    leaves the tag unchanged, `null` clears it, a value sets it. The value \
                    must be a live `label` (resp. `status`) taxonomy row in this project, \
                    otherwise the whole change is an `invalid_request` conflict.

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

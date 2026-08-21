package com.noveltea.snapshot;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.snapshot.SnapshotService.SnapshotDetail;
import com.noveltea.snapshot.SnapshotService.SnapshotSummary;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Snapshots", description = "Point-in-time document copies: capture, list, restore.")
public class SnapshotController {

    private final SnapshotService snapshots;
    private final ProjectAccess access;

    public SnapshotController(SnapshotService snapshots, ProjectAccess access) {
        this.snapshots = snapshots;
        this.access = access;
    }

    /**
     * @param automatic omitted or false means a manual snapshot, which syncs. Automatic
     *     snapshots stay local and are pruned; manual ones sync and are kept forever.
     */
    public record CaptureRequest(String label, Boolean automatic) {}

    public record RestoreRequest(long baseVersion) {}

    @PostMapping("/documents/{documentId}/snapshots")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> capture(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID documentId,
            @RequestBody(required = false) CaptureRequest request) {
        access.requireWritableItem(user, documentId);
        boolean automatic = request != null && Boolean.TRUE.equals(request.automatic());
        String label = request == null ? null : request.label();
        return Map.of("id", snapshots.capture(documentId, label, automatic, user.deviceId()));
    }

    /** Summaries only — a revision list must never ship the whole manuscript. */
    @GetMapping("/documents/{documentId}/snapshots")
    public List<SnapshotSummary> list(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID documentId) {
        access.requireWritableItem(user, documentId);
        return snapshots.list(documentId);
    }

    @GetMapping("/snapshots/{snapshotId}")
    public SnapshotDetail get(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID snapshotId) {
        access.requireReadable(user, snapshots.projectOf(snapshotId));
        return snapshots.get(snapshotId);
    }

    @Operation(
            summary = "Restore a document to a snapshot",
            description = "Itself undoable: the pre-restore state is captured automatically "
                    + "first, so restoring to the wrong version does not lose the newer one. "
                    + "Refuses a stale baseVersion so it cannot clobber an edit made on "
                    + "another device meanwhile.")
    @PostMapping("/snapshots/{snapshotId}/restore")
    public Map<String, Long> restore(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID snapshotId,
            @RequestBody RestoreRequest request) {
        access.requireWritable(user, snapshots.projectOf(snapshotId));
        return Map.of("version", snapshots.restore(snapshotId, request.baseVersion(), user.deviceId()));
    }

    @DeleteMapping("/snapshots/{snapshotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID snapshotId) {
        access.requireWritable(user, snapshots.projectOf(snapshotId));
        snapshots.delete(snapshotId, user.deviceId());
    }
}

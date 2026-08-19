package com.noveltea.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MergeController {

    private final MergeService merge;
    private final ProjectAccess access;

    public MergeController(MergeService merge, ProjectAccess access) {
        this.merge = merge;
        this.access = access;
    }

    /** @param baseVersion the original's version the author was shown while merging. */
    public record ResolveRequest(JsonNode content, long baseVersion) {}

    @GetMapping("/projects/{projectId}/conflicts")
    public List<MergeService.ConflictSummary> list(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);
        return merge.listConflicts(projectId);
    }

    @GetMapping("/conflicts/{copyId}")
    public MergeService.ConflictDetail detail(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID copyId) {
        access.requireWritableItem(user, copyId);
        return merge.get(copyId);
    }

    @PostMapping("/conflicts/{copyId}/resolve")
    public Map<String, Object> resolve(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID copyId,
            @RequestBody ResolveRequest request) {
        access.requireWritableItem(user, copyId);
        long version = merge.resolve(copyId, request.content(), request.baseVersion(), user.deviceId());
        return Map.of("originalVersion", version);
    }
}

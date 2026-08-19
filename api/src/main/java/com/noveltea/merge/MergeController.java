package com.noveltea.merge;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.merge.MergeExceptions.NotAConflictCopy;
import com.noveltea.merge.MergeExceptions.StaleOriginal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MergeController {

    private final MergeService merge;

    public MergeController(MergeService merge) {
        this.merge = merge;
    }

    /** @param baseVersion the original's version the author was shown while merging. */
    public record ResolveRequest(JsonNode content, long baseVersion) {}

    @GetMapping("/projects/{projectId}/conflicts")
    public List<MergeService.ConflictSummary> list(@PathVariable UUID projectId) {
        return merge.listConflicts(projectId);
    }

    @GetMapping("/conflicts/{copyId}")
    public MergeService.ConflictDetail detail(@PathVariable UUID copyId) {
        return merge.get(copyId);
    }

    @PostMapping("/conflicts/{copyId}/resolve")
    public Map<String, Object> resolve(
            @PathVariable UUID copyId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId,
            @RequestBody ResolveRequest request) {
        long version = merge.resolve(copyId, request.content(), request.baseVersion(), deviceId);
        return Map.of("originalVersion", version);
    }

    @ExceptionHandler(NotAConflictCopy.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(NotAConflictCopy e) {
        return Map.of("error", "not_a_conflict_copy", "message", e.getMessage());
    }

    @ExceptionHandler(StaleOriginal.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> stale(StaleOriginal e) {
        return Map.of(
                "error", "stale_original",
                "message", e.getMessage(),
                "currentVersion", e.currentVersion());
    }
}

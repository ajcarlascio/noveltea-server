package com.noveltea.sync;

import com.noveltea.sync.dto.SyncDtos.PullResponse;
import com.noveltea.sync.dto.SyncDtos.PushRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sync")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    /**
     * TODO(auth): projectId is trusted from the path and deviceId from a header. Once
     * device-paired JWTs exist, both must come from the authenticated principal, and
     * this endpoint must filter the feed by the caller's role and subtree scope.
     */
    @GetMapping
    public PullResponse pull(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "200") int limit) {
        return sync.pull(projectId, since, limit);
    }

    @PostMapping
    public PushResponse push(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId,
            @RequestBody PushRequest request) {
        return sync.push(projectId, deviceId, request.changes());
    }
}

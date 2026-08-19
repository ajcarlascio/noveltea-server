package com.noveltea.sync;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.sync.dto.SyncDtos.PullResponse;
import com.noveltea.sync.dto.SyncDtos.PushRequest;
import com.noveltea.sync.dto.SyncDtos.PushResponse;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sync")
public class SyncController {

    private final SyncService sync;
    private final ProjectAccess access;

    public SyncController(SyncService sync, ProjectAccess access) {
        this.sync = sync;
        this.access = access;
    }

    @GetMapping
    public PullResponse pull(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "200") int limit) {
        access.requireReadable(user, projectId);
        return sync.pull(projectId, user.deviceId(), since, limit);
    }

    /**
     * The device is taken from the access token, never from a header — a client cannot
     * attribute its writes to somebody else's device.
     */
    @PostMapping
    public PushResponse push(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody PushRequest request) {
        access.requireWritable(user, projectId);
        return sync.push(projectId, user.deviceId(), request.changes());
    }
}

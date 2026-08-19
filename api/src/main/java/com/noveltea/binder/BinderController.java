package com.noveltea.binder;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BinderController {

    private final BinderService binder;
    private final ProjectAccess access;

    public BinderController(BinderService binder, ProjectAccess access) {
        this.binder = binder;
        this.access = access;
    }

    public record CreateItemRequest(UUID parentId, String type, String title, UUID afterSiblingId) {}

    public record UpdateItemRequest(String title, UUID parentId, UUID afterSiblingId) {}

    @GetMapping("/projects/{projectId}/binder")
    public List<BinderNode> tree(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);
        return binder.tree(projectId);
    }

    @PostMapping("/projects/{projectId}/binder-items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody CreateItemRequest request) {
        access.requireWritable(user, projectId);
        UUID id = binder.create(projectId, user.deviceId(), request.parentId(), request.type(),
                request.title(), request.afterSiblingId());
        return Map.of("id", id);
    }

    /** Rename and move are one endpoint because a drag can do both at once. */
    @PatchMapping("/binder-items/{itemId}")
    public void update(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID itemId,
            @RequestBody UpdateItemRequest request) {
        access.requireWritableItem(user, itemId);
        if (request.title() != null) {
            binder.rename(itemId, request.title(), user.deviceId());
        }
        if (request.parentId() != null || request.afterSiblingId() != null) {
            binder.move(itemId, request.parentId(), request.afterSiblingId(), user.deviceId());
        }
    }

    @PostMapping("/binder-items/{itemId}/trash")
    public void trash(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID itemId) {
        access.requireWritableItem(user, itemId);
        binder.trash(itemId, user.deviceId());
    }

    @PostMapping("/binder-items/{itemId}/restore")
    public void restore(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID itemId) {
        access.requireWritableItem(user, itemId);
        binder.restore(itemId, user.deviceId());
    }

    @DeleteMapping("/projects/{projectId}/trash")
    public Map<String, Integer> emptyTrash(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireWritable(user, projectId);
        return Map.of("deleted", binder.emptyTrash(projectId, user.deviceId()));
    }
}

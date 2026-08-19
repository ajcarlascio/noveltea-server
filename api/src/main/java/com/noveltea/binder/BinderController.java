package com.noveltea.binder;

import com.noveltea.binder.BinderExceptions.BinderCycle;
import com.noveltea.binder.BinderExceptions.BinderItemNotFound;
import com.noveltea.binder.BinderExceptions.CrossProjectMove;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class BinderController {

    private final BinderService binder;

    public BinderController(BinderService binder) {
        this.binder = binder;
    }

    public record CreateItemRequest(UUID parentId, String type, String title, UUID afterSiblingId) {}

    public record UpdateItemRequest(String title, UUID parentId, UUID afterSiblingId) {}

    @GetMapping("/projects/{projectId}/binder")
    public List<BinderNode> tree(@PathVariable UUID projectId) {
        return binder.tree(projectId);
    }

    @PostMapping("/projects/{projectId}/binder-items")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId,
            @RequestBody CreateItemRequest request) {
        UUID id = binder.create(
                projectId, deviceId, request.parentId(), request.type(), request.title(), request.afterSiblingId());
        return Map.of("id", id);
    }

    /** Rename and move are one endpoint because a drag can do both at once. */
    @PatchMapping("/binder-items/{itemId}")
    public void update(
            @PathVariable UUID itemId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId,
            @RequestBody UpdateItemRequest request) {
        if (request.title() != null) {
            binder.rename(itemId, request.title(), deviceId);
        }
        if (request.parentId() != null || request.afterSiblingId() != null) {
            binder.move(itemId, request.parentId(), request.afterSiblingId(), deviceId);
        }
    }

    @PostMapping("/binder-items/{itemId}/trash")
    public void trash(
            @PathVariable UUID itemId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId) {
        binder.trash(itemId, deviceId);
    }

    @PostMapping("/binder-items/{itemId}/restore")
    public void restore(
            @PathVariable UUID itemId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId) {
        binder.restore(itemId, deviceId);
    }

    @DeleteMapping("/projects/{projectId}/trash")
    public Map<String, Integer> emptyTrash(
            @PathVariable UUID projectId,
            @RequestHeader(value = "X-Device-Id", required = false) UUID deviceId) {
        return Map.of("deleted", binder.emptyTrash(projectId, deviceId));
    }

    @ExceptionHandler(BinderItemNotFound.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> notFound(BinderItemNotFound e) {
        return Map.of("error", "not_found", "message", e.getMessage());
    }

    @ExceptionHandler({BinderCycle.class, CrossProjectMove.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException e) {
        return Map.of("error", "invalid_move", "message", e.getMessage());
    }
}

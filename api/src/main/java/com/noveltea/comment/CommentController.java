package com.noveltea.comment;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.comment.CommentService.Comment;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CommentController {

    private final CommentService comments;
    private final ProjectAccess access;

    public CommentController(CommentService comments, ProjectAccess access) {
        this.comments = comments;
        this.access = access;
    }

    /** @param anchor {from, to, quotedText}; omit for an unanchored note or a reply. */
    public record CreateCommentRequest(String body, JsonNode anchor, UUID parentCommentId) {}

    public record EditCommentRequest(String body) {}

    public record ResolveRequest(boolean resolved) {}

    @GetMapping("/documents/{documentId}/comments")
    public List<Comment> list(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID documentId) {
        access.requireWritableItem(user, documentId);
        return comments.forDocument(documentId);
    }

    @PostMapping("/documents/{documentId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, UUID> create(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID documentId,
            @RequestBody CreateCommentRequest request) {
        access.requireWritableItem(user, documentId);
        return Map.of("id", comments.create(
                documentId, user.userId(), user.deviceId(),
                request.body(), request.anchor(), request.parentCommentId()));
    }

    @PatchMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void edit(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID commentId,
            @RequestBody EditCommentRequest request) {
        access.requireWritable(user, comments.projectOf(commentId));
        comments.edit(commentId, user.userId(), request.body(), user.deviceId());
    }

    /** Resolving is a shared editorial act, so it is not restricted to the author. */
    @PostMapping("/comments/{commentId}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID commentId,
            @RequestBody ResolveRequest request) {
        access.requireWritable(user, comments.projectOf(commentId));
        comments.setResolved(commentId, user.userId(), request.resolved(), user.deviceId());
    }

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID commentId) {
        access.requireWritable(user, comments.projectOf(commentId));
        comments.delete(commentId, user.userId(), user.deviceId());
    }
}

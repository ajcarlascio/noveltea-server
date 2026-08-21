package com.noveltea.document;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Documents", description = "Document bodies, for clients rebuilding a replica.")
public class DocumentController {

    private final DocumentService documents;
    private final ProjectAccess access;

    public DocumentController(DocumentService documents, ProjectAccess access) {
        this.documents = documents;
        this.access = access;
    }

    @GetMapping("/projects/{projectId}/documents")
    @Operation(
            summary = "Read document bodies for a project",
            description =
                    """
                    Every document's current content, paged. This is what a client uses after \
                    `GET /sync` answers `resyncRequired`: the change feed carries content only \
                    on rows appended since a cursor, so a client rebuilding from scratch cannot \
                    recover the body of a document nobody has touched recently.

                    Paged like the feed — a page stops at whichever comes first, rows or bytes \
                    — so a project of full documents has a predictable size on mobile data. \
                    Pass the `nextCursor` from one page as `after` on the next, and stop when \
                    `hasMore` is false.

                    Ordered by id, which is arbitrary but stable. Trashed documents are \
                    included, because trashing is a move and the item is still restorable; \
                    tombstoned ones are not, because those are gone.
                    """)
    public DocumentService.DocumentPage documents(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID after,
            @RequestParam(required = false) Integer limit) {
        access.requireReadable(user, projectId);
        return documents.bodies(projectId, after, limit);
    }
}

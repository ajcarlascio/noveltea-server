package com.noveltea.search;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.search.SearchService.SearchResults;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "Search", description = "Full-text search across a project's titles, synopses, body text and notes.")
public class SearchController {

    private final SearchService search;
    private final ProjectAccess access;

    public SearchController(SearchService search, ProjectAccess access) {
        this.search = search;
        this.access = access;
    }

    @Operation(
            summary = "Search a project",
            description = "Weighted: title beats synopsis beats body beats notes. Synopses "
                    + "and notes are searchable although they are never exported — they are "
                    + "what an author searches to find a scene again. Trashed items are "
                    + "excluded unless includeTrashed=true; tombstoned ones never appear. "
                    + "Malformed query syntax yields no results rather than an error.")
    @GetMapping("/search")
    public SearchResults search(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "false") boolean includeTrashed,
            @RequestParam(defaultValue = "50") int limit) {
        access.requireReadable(user, projectId);
        return search.search(projectId, query, includeTrashed, limit);
    }
}

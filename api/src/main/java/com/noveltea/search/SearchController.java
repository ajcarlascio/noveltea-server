package com.noveltea.search;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.search.SearchService.SearchResults;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class SearchController {

    private final SearchService search;
    private final ProjectAccess access;

    public SearchController(SearchService search, ProjectAccess access) {
        this.search = search;
        this.access = access;
    }

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

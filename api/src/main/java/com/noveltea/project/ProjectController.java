package com.noveltea.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectAccess access;

    public ProjectController(ProjectService projects, ProjectAccess access) {
        this.projects = projects;
        this.access = access;
    }

    public record CreateProjectRequest(String title, JsonNode settings) {}

    /** Null fields mean "leave unchanged" — a partial update must not blank a title. */
    public record UpdateProjectRequest(String title, JsonNode settings) {}

    @GetMapping
    public List<Project> list(@AuthenticationPrincipal CurrentUser user) {
        return projects.list(user.userId());
    }

    @GetMapping("/deleted")
    public List<Project> listDeleted(@AuthenticationPrincipal CurrentUser user) {
        return projects.listDeleted(user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project create(
            @AuthenticationPrincipal CurrentUser user, @RequestBody CreateProjectRequest request) {
        return projects.create(user.userId(), request.title(), request.settings());
    }

    @GetMapping("/{projectId}")
    public Project get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);
        return projects.get(projectId, false);
    }

    @PatchMapping("/{projectId}")
    public Project update(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody UpdateProjectRequest request) {
        access.requireWritable(user, projectId);
        return projects.update(projectId, request.title(), request.settings());
    }

    /** Hides the project. Recoverable via restore until explicitly purged. */
    @DeleteMapping("/{projectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireWritable(user, projectId);
        projects.delete(projectId);
    }

    @PostMapping("/{projectId}/restore")
    public Project restore(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireOwnerIncludingDeleted(user, projectId);
        return projects.restore(projectId);
    }

    /**
     * Destroys the project and everything in it. Separate verb and path from delete on
     * purpose: this is not something a client should be able to reach by accident.
     */
    @DeleteMapping("/{projectId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireOwnerIncludingDeleted(user, projectId);
        projects.purge(projectId);
    }
}

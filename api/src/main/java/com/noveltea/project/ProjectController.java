package com.noveltea.project;

import com.fasterxml.jackson.databind.JsonNode;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Project lifecycle: create, read, update, soft-delete, restore, purge.")
public class ProjectController {

    private final ProjectService projects;
    private final ProjectAccess access;

    public ProjectController(ProjectService projects, ProjectAccess access) {
        this.projects = projects;
        this.access = access;
    }

    public record CreateProjectRequest(UUID id, String title, JsonNode settings) {}

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
        return projects.create(user.userId(), request.id(), request.title(), request.settings());
    }

    @Operation(
            summary = "Get a project",
            description = "A project the caller does not own answers 404, identically to one "
                    + "that does not exist — a 403 would confirm it exists to someone who was "
                    + "never granted access.")
    @ApiResponse(responseCode = "404", description = "Not found, or not visible to the caller.")
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

    @Operation(
            summary = "Restore a soft-deleted project",
            description = "The one place a deleted project is still visible to its owner — "
                    + "everywhere else, deletion makes it answer 404 like it never existed.")
    @PostMapping("/{projectId}/restore")
    public Project restore(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireOwnerIncludingDeleted(user, projectId);
        return projects.restore(projectId);
    }

    /**
     * Destroys the project and everything in it. Separate verb and path from delete on
     * purpose: this is not something a client should be able to reach by accident.
     */
    @Operation(
            summary = "Permanently destroy a project and everything in it",
            description = "Separate verb and path from delete on purpose: not something a "
                    + "client should reach by accident. Irreversible.")
    @DeleteMapping("/{projectId}/purge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void purge(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireOwnerIncludingDeleted(user, projectId);
        projects.purge(projectId);
    }
}

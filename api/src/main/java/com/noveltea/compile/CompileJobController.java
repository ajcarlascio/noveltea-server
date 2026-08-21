package com.noveltea.compile;

import com.noveltea.auth.CurrentUser;
import com.noveltea.compile.CompileService.Artifact;
import com.noveltea.compile.CompileService.CompileJob;
import com.noveltea.compile.CompileService.CompileRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(
        name = "Compile Jobs",
        description = "Asynchronous manuscript export: submit, poll for completion, download. "
                + "A worker process does the actual rendering; these endpoints only queue and "
                + "report on it.")
public class CompileJobController {

    private final CompileService compile;

    public CompileJobController(CompileService compile) {
        this.compile = compile;
    }

    @Operation(
            summary = "Queue a compile job",
            description = """
                    Returns immediately with a job id; a separate worker process does the \
                    actual rendering. Poll GET /compile-jobs/{jobId} for status, then \
                    GET /compile-jobs/{jobId}/download once it reports a terminal status.

                    Answers 501 (unavailable_in_this_edition) for a format or destination \
                    this build does not ship — this is Core, which ships only md/html/txt \
                    and the `download`/`server` destinations; `cloud` and every other export \
                    format are commercial. Identical pending requests are deduplicated: \
                    resubmitting the same not-yet-finished job returns the existing job id \
                    rather than queuing a duplicate.""")
    @PostMapping("/projects/{projectId}/compile")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> submit(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody CompileRequest request) {
        return Map.of("jobId", compile.submit(user, projectId, request));
    }

    @Operation(summary = "Poll a compile job's status", description = "isTerminal on the "
            + "returned job is what a client should loop on before calling .../download.")
    @GetMapping("/compile-jobs/{jobId}")
    public CompileJob status(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID jobId) {
        return compile.get(user, jobId);
    }

    @GetMapping("/projects/{projectId}/compile-jobs")
    public List<CompileJob> recent(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "20") int limit) {
        return compile.recent(user, projectId, limit);
    }

    /**
     * Streams the finished file.
     *
     * <p>Streamed rather than buffered: a compiled manuscript can be large, and holding one
     * in memory per concurrent download is how an export feature takes the server down.
     */
    @Operation(
            summary = "Download a finished compile job's file",
            description = "410 (Gone) once a `download`-destination export has expired "
                    + "(noveltea.compile.download-ttl) or was never produced. A `server` "
                    + "destination export is kept indefinitely in the operator's mounted "
                    + "storage instead.")
    @GetMapping("/compile-jobs/{jobId}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID jobId) {
        Artifact artifact = compile.openArtifact(user, jobId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + sanitiseFilename(artifact.filename()) + "\"")
                .contentType(MediaType.parseMediaType(artifact.contentType()))
                .contentLength(artifact.size())
                .body(new InputStreamResource(artifact.stream()));
    }

    /** Quotes and control characters in a header value would let a title split the response. */
    private static String sanitiseFilename(String filename) {
        return filename.replaceAll("[\\r\\n\"\\\\]", "_");
    }
}

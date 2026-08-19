package com.noveltea.compile;

import com.noveltea.auth.CurrentUser;
import com.noveltea.compile.CompileService.Artifact;
import com.noveltea.compile.CompileService.CompileJob;
import com.noveltea.compile.CompileService.CompileRequest;
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
public class CompileJobController {

    private final CompileService compile;

    public CompileJobController(CompileService compile) {
        this.compile = compile;
    }

    /** Queues an export. Returns immediately; the worker does the rendering. */
    @PostMapping("/projects/{projectId}/compile")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, UUID> submit(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable UUID projectId,
            @RequestBody CompileRequest request) {
        return Map.of("jobId", compile.submit(user, projectId, request));
    }

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

package com.noveltea.compile;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.model.ExportFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/compile")
public class CompileController {

    private final ExportProvider exports;
    private final ProjectAccess access;

    public CompileController(ExportProvider exports, ProjectAccess access) {
        this.exports = exports;
        this.access = access;
    }

    /**
     * What this installation can produce, and what it cannot.
     *
     * <p>Reporting the unavailable formats explicitly, rather than omitting them, lets a
     * client show them as an upgrade rather than pretending they do not exist.
     */
    @GetMapping("/formats")
    public Map<String, List<String>> formats(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);

        List<String> supported = Arrays.stream(ExportFormat.values())
                .filter(exports::supports).map(ExportFormat::wire).toList();
        List<String> unavailable = Arrays.stream(ExportFormat.values())
                .filter(format -> !exports.supports(format)).map(ExportFormat::wire).toList();

        return Map.of("supported", supported, "unavailable", unavailable);
    }
}

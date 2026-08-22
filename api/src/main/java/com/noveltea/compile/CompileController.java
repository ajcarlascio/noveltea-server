package com.noveltea.compile;

import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.model.CompileDestination;
import com.noveltea.model.ExportFormat;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/compile")
@Tag(name = "Compile", description = "What this installation can export to.")
public class CompileController {

    private final ExportProvider exports;
    private final DestinationProvider destinations;
    private final ProjectAccess access;

    public CompileController(
            ExportProvider exports, DestinationProvider destinations, ProjectAccess access) {
        this.exports = exports;
        this.destinations = destinations;
        this.access = access;
    }

    /**
     * What this installation can produce, and what it cannot.
     *
     * <p>Reporting the unavailable formats explicitly, rather than omitting them, lets a
     * client show them as an upgrade rather than pretending they do not exist.
     */
    @Operation(
            summary = "List supported and unavailable export formats",
            description = "Unavailable formats are reported explicitly rather than omitted, "
                    + "so a client can show them as an upgrade rather than pretend they do "
                    + "not exist. This is a Core build: commercial formats (rtf, docx, odt, "
                    + "epub, pdf) are always unavailable here; requesting one from "
                    + "POST .../compile answers 501.")
    @GetMapping("/formats")
    public Map<String, List<String>> formats(
            @AuthenticationPrincipal CurrentUser user, @PathVariable UUID projectId) {
        access.requireReadable(user, projectId);

        List<String> supported = Arrays.stream(ExportFormat.values())
                .filter(exports::supports).map(ExportFormat::wire).toList();
        List<String> unavailable = Arrays.stream(ExportFormat.values())
                .filter(format -> !exports.supports(format)).map(ExportFormat::wire).toList();

        // Destinations answer the same question about the same request and are gated the
        // same way, so they are reported the same way. Without them a client cannot show
        // cloud as an upgrade — it can only offer it and let the submission fail with a
        // 501, which is the "pretend it does not exist" outcome inverted.
        List<String> availableDestinations = Arrays.stream(CompileDestination.values())
                .filter(destinations::supports).map(CompileDestination::wire).toList();
        List<String> unavailableDestinations = Arrays.stream(CompileDestination.values())
                .filter(destination -> !destinations.supports(destination))
                .map(CompileDestination::wire).toList();

        return Map.of(
                "supported", supported,
                "unavailable", unavailable,
                "destinations", availableDestinations,
                "unavailableDestinations", unavailableDestinations);
    }
}

package com.noveltea.compile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.auth.CurrentUser;
import com.noveltea.auth.ProjectAccess;
import com.noveltea.compile.CompileExceptions.ArtifactUnavailable;
import com.noveltea.compile.CompileExceptions.TooManyPendingCompiles;
import com.noveltea.compile.CompileExceptions.UnavailableInThisEdition;
import com.noveltea.model.CompileDestination;
import com.noveltea.model.CompileJobStatus;
import com.noveltea.config.LimitProperties;
import com.noveltea.model.ExportFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues export jobs and hands back their results.
 *
 * <p>Compiling is done by the Node worker, which owns the document schema. This side only
 * decides whether a job is allowed, records it, and serves whatever the worker produced.
 * Nothing here interprets document structure.
 */
@Service
public class CompileService {

    private static final Logger log = LoggerFactory.getLogger(CompileService.class);
    /** The worker LISTENs on this; the payload is only a nudge, never authoritative. */
    public static final String NOTIFY_CHANNEL = "noveltea_compile";

    private final JdbcClient jdbc;
    private final ProjectAccess access;
    private final ExportProvider exports;
    private final DestinationProvider destinations;
    private final CompileProperties properties;
    private final ObjectMapper mapper;
    private final LimitProperties limits;

    public CompileService(
            JdbcClient jdbc,
            ProjectAccess access,
            ExportProvider exports,
            DestinationProvider destinations,
            CompileProperties properties,
            ObjectMapper mapper,
            LimitProperties limits) {
        this.limits = limits;
        this.jdbc = jdbc;
        this.access = access;
        this.exports = exports;
        this.destinations = destinations;
        this.properties = properties;
        this.mapper = mapper;
    }

    public record CompileRequest(
            String format, String destination, UUID presetId, JsonNode inlineConfig) {}

    public record CompileJob(
            UUID id,
            UUID projectId,
            String format,
            String destination,
            String status,
            String outputFilename,
            Long outputBytes,
            Integer wordCount,
            JsonNode warnings,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt,
            OffsetDateTime expiresAt) {

        public boolean isTerminal() {
            return CompileJobStatus.fromWire(status).map(CompileJobStatus::isTerminal).orElse(false);
        }
    }

    // ---------------------------------------------------------------- submit

    @Transactional
    public UUID submit(CurrentUser user, UUID projectId, CompileRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        access.requireWritable(user, projectId);

        ExportFormat format = ExportFormat.fromWire(request.format())
                .orElseThrow(() -> new IllegalArgumentException("unknown format: " + request.format()));
        CompileDestination destination = CompileDestination
                .fromWire(request.destination() == null ? "download" : request.destination())
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown destination: " + request.destination()));

        if (!exports.supports(format)) {
            throw new UnavailableInThisEdition(
                    format.wire() + " export is not available in this edition");
        }
        if (!destinations.supports(destination)) {
            throw new UnavailableInThisEdition(
                    destination.wire() + " storage is not available in this edition");
        }
        if (request.presetId() == null && request.inlineConfig() == null) {
            throw new IllegalArgumentException("a compile needs a preset_id or an inline config");
        }

        // An identical export is already waiting: hand back the job that exists rather than
        // rendering the same manuscript twice. This is what stops a client retrying a slow
        // request from queueing ten copies of it.
        Optional<UUID> duplicate = jdbc.sql("""
                SELECT id FROM compile_job
                 WHERE project_id = :projectId AND format = :format AND destination = :destination
                   AND status IN ('queued', 'running')
                   AND preset_id IS NOT DISTINCT FROM CAST(:presetId AS uuid)
                   -- Comparing the config too: without it, an author who tweaks an inline
                   -- config and re-exports is handed the PREVIOUS job's artifact, which
                   -- silently will not match what they asked for.
                   AND inline_config IS NOT DISTINCT FROM CAST(:inlineConfig AS jsonb)
                 ORDER BY created_at LIMIT 1
                """)
                .param("projectId", projectId)
                .param("format", format.wire())
                .param("destination", destination.wire())
                .param("presetId", request.presetId())
                .param("inlineConfig",
                        request.inlineConfig() == null ? null : request.inlineConfig().toString())
                .query(UUID.class)
                .optional();
        if (duplicate.isPresent()) {
            return duplicate.get();
        }

        // Bounded by how many are WAITING, not by how often they are requested. An author
        // tuning a preset exports repeatedly and legitimately; each finished job frees its
        // slot immediately, so that workflow never meets this limit.
        long pending = jdbc.sql("""
                SELECT count(*) FROM compile_job
                 WHERE requested_by_user_id = :userId AND status IN ('queued', 'running')
                """)
                .param("userId", user.userId())
                .query(Long.class)
                .single();
        if (pending >= limits.maxPendingCompilesPerUser()) {
            throw new TooManyPendingCompiles(limits.maxPendingCompilesPerUser());
        }

        UUID jobId = UUID.randomUUID();
        OffsetDateTime expiresAt = destination == CompileDestination.DOWNLOAD
                ? OffsetDateTime.now().plus(properties.downloadTtl())
                : null;

        jdbc.sql("""
                INSERT INTO compile_job
                    (id, project_id, preset_id, inline_config, format, destination, status,
                     requested_by_user_id, requested_by_device_id, expires_at)
                VALUES (:id, :projectId, :presetId, CAST(:inlineConfig AS jsonb), :format,
                        :destination, 'queued', :userId, :deviceId, :expiresAt)
                """)
                .param("id", jobId)
                .param("projectId", projectId)
                .param("presetId", request.presetId())
                .param("inlineConfig", request.inlineConfig() == null ? null : request.inlineConfig().toString())
                .param("format", format.wire())
                .param("destination", destination.wire())
                .param("userId", user.userId())
                .param("deviceId", user.deviceId())
                .param("expiresAt", expiresAt)
                .update();

        // Sent inside the transaction: Postgres holds notifications until commit, so the
        // worker cannot wake before the row it is being told about is visible.
        // pg_notify is a function call, so this is a query with a (discarded) result row,
        // not an update.
        jdbc.sql("SELECT pg_notify(:channel, :payload)")
                .param("channel", NOTIFY_CHANNEL)
                .param("payload", jobId.toString())
                .query()
                .listOfRows();

        return jobId;
    }

    // ---------------------------------------------------------------- status

    public CompileJob get(CurrentUser user, UUID jobId) {
        Objects.requireNonNull(jobId, "jobId");
        Map<String, Object> row = jdbc.sql("""
                SELECT id, project_id, format, destination, status, output_filename, output_bytes,
                       word_count, warnings::text AS warnings_json, error_message,
                       created_at, finished_at, expires_at
                  FROM compile_job WHERE id = :id
                """)
                .param("id", jobId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElseThrow(() -> new com.noveltea.auth.AuthExceptions.AccessDenied("no such job"));

        // A job is only visible to someone who can reach its project.
        access.requireReadable(user, (UUID) row.get("project_id"));

        return new CompileJob(
                (UUID) row.get("id"),
                (UUID) row.get("project_id"),
                (String) row.get("format"),
                (String) row.get("destination"),
                (String) row.get("status"),
                (String) row.get("output_filename"),
                row.get("output_bytes") == null ? null : ((Number) row.get("output_bytes")).longValue(),
                row.get("word_count") == null ? null : ((Number) row.get("word_count")).intValue(),
                readJson((String) row.get("warnings_json")),
                (String) row.get("error_message"),
                toOffset(row.get("created_at")),
                toOffset(row.get("finished_at")),
                toOffset(row.get("expires_at")));
    }

    public List<CompileJob> recent(CurrentUser user, UUID projectId, int limit) {
        access.requireReadable(user, projectId);
        return jdbc.sql("SELECT id FROM compile_job WHERE project_id = :p ORDER BY created_at DESC LIMIT :n")
                .param("p", projectId).param("n", Math.min(Math.max(limit, 1), 100))
                .query(UUID.class).list()
                .stream().map(id -> get(user, id)).toList();
    }

    // -------------------------------------------------------------- artifact

    public record Artifact(InputStream stream, String filename, long size, String contentType) {}

    /**
     * Opens a finished export for download.
     *
     * <p>The stored path is never trusted as given: it is resolved and checked to be inside
     * a configured directory, so a corrupted or tampered row cannot turn this into an
     * arbitrary file read.
     */
    public Artifact openArtifact(CurrentUser user, UUID jobId) {
        CompileJob job = get(user, jobId);

        if (!"done".equals(job.status())) {
            throw new ArtifactUnavailable("this export is " + job.status());
        }
        if (job.expiresAt() != null && job.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new ArtifactUnavailable("this export has expired");
        }

        String stored = jdbc.sql("SELECT output_path FROM compile_job WHERE id = :id")
                .param("id", jobId).query(String.class).optional().orElse(null);
        if (stored == null) {
            throw new ArtifactUnavailable("this export produced no file");
        }

        Path path = Path.of(stored).toAbsolutePath().normalize();
        if (!isInsideConfiguredRoots(path)) {
            log.error("compile_job {} points outside the configured export roots: {}", jobId, path);
            throw new ArtifactUnavailable("this export is no longer available");
        }
        if (!Files.isRegularFile(path)) {
            throw new ArtifactUnavailable("this export has been removed");
        }

        try {
            return new Artifact(
                    Files.newInputStream(path),
                    job.outputFilename() == null ? path.getFileName().toString() : job.outputFilename(),
                    Files.size(path),
                    contentTypeFor(job.format()));
        } catch (IOException e) {
            throw new ArtifactUnavailable("this export could not be read");
        }
    }

    private boolean isInsideConfiguredRoots(Path candidate) {
        for (String root : List.of(properties.stagingPath(), properties.storagePath())) {
            Path normalisedRoot = Path.of(root).toAbsolutePath().normalize();
            if (candidate.startsWith(normalisedRoot)) {
                return true;
            }
        }
        return false;
    }

    private static String contentTypeFor(String format) {
        return switch (format) {
            case "html" -> "text/html; charset=utf-8";
            case "md" -> "text/markdown; charset=utf-8";
            case "txt" -> "text/plain; charset=utf-8";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "odt" -> "application/vnd.oasis.opendocument.text";
            case "epub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "rtf" -> "application/rtf";
            default -> "application/octet-stream";
        };
    }

    private JsonNode readJson(String json) {
        try {
            return json == null ? mapper.createArrayNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createArrayNode();
        }
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) return null;
        return value instanceof OffsetDateTime odt
                ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }
}

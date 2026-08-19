package com.noveltea.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.auth.AuthExceptions.AccessDenied;
import com.noveltea.auth.AuthService;
import com.noveltea.auth.AuthService.Session;
import com.noveltea.auth.CurrentUser;
import com.noveltea.compile.CompileExceptions.ArtifactUnavailable;
import com.noveltea.compile.CompileExceptions.UnavailableInThisEdition;
import com.noveltea.compile.CompileService.CompileRequest;
import com.noveltea.support.AbstractPostgresTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CompileServiceTest extends AbstractPostgresTest {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired CompileService compile;
    @Autowired AuthService auth;
    @Autowired CompileProperties properties;
    @Autowired ObjectMapper mapper;

    private record Actor(Session session, CurrentUser principal, UUID projectId) {}

    private Actor actor() {
        Session session = auth.register("c-" + UUID.randomUUID() + "@example.com", PASSWORD, "L", "web");
        UUID projectId = UUID.randomUUID();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :o, 'Novel')")
                .param("id", projectId).param("o", session.userId()).update();
        return new Actor(session, new CurrentUser(session.userId(), session.deviceId()), projectId);
    }

    private CompileRequest request(String format, String destination) {
        return new CompileRequest(format, destination, null, mapper.createObjectNode());
    }

    @Test
    @DisplayName("submitting queues a job the worker can find")
    void submitQueuesJob() {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("md", "download"));

        var job = compile.get(me.principal(), jobId);
        assertThat(job.status()).isEqualTo("queued");
        assertThat(job.format()).isEqualTo("md");
        assertThat(job.destination()).isEqualTo("download");
        assertThat(job.expiresAt()).as("download artifacts are purged, so they need a deadline").isNotNull();
    }

    @Test
    @DisplayName("a server-destined job has no expiry — the operator owns that storage")
    void serverJobsDoNotExpire() {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("txt", "server"));
        assertThat(compile.get(me.principal(), jobId).expiresAt()).isNull();
    }

    @Test
    @DisplayName("cloud storage is refused by a Core build, and no job is created")
    void cloudDestinationRefused() {
        Actor me = actor();
        assertThatThrownBy(() -> compile.submit(me.principal(), me.projectId(), request("md", "cloud")))
                .isInstanceOf(UnavailableInThisEdition.class)
                .hasMessageContaining("cloud");
        assertThat(jdbc.sql("SELECT count(*) FROM compile_job WHERE project_id = :p")
                .param("p", me.projectId()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a commercial format is refused by a Core build")
    void commercialFormatRefused() {
        Actor me = actor();
        for (String format : new String[] {"docx", "epub", "pdf", "rtf", "odt"}) {
            assertThatThrownBy(() -> compile.submit(me.principal(), me.projectId(), request(format, "download")))
                    .isInstanceOf(UnavailableInThisEdition.class);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM compile_job WHERE project_id = :p")
                .param("p", me.projectId()).query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("unknown formats and destinations are rejected outright")
    void unknownValuesRejected() {
        Actor me = actor();
        assertThatThrownBy(() -> compile.submit(me.principal(), me.projectId(), request("wordperfect", "download")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile.submit(me.principal(), me.projectId(), request("md", "usb-stick")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a compile with neither a preset nor an inline config is rejected")
    void needsAConfiguration() {
        Actor me = actor();
        assertThatThrownBy(() -> compile.submit(me.principal(), me.projectId(),
                new CompileRequest("md", "download", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a job belongs to its project: no one else can see it")
    void jobsAreScopedToTheirProject() {
        Actor owner = actor();
        Actor stranger = actor();
        UUID jobId = compile.submit(owner.principal(), owner.projectId(), request("md", "download"));

        assertThatThrownBy(() -> compile.get(stranger.principal(), jobId)).isInstanceOf(AccessDenied.class);
        assertThatThrownBy(() -> compile.openArtifact(stranger.principal(), jobId))
                .isInstanceOf(AccessDenied.class);
    }

    @Test
    @DisplayName("downloading before the worker finishes says so rather than 404ing")
    void downloadBeforeCompletion() {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("md", "download"));

        assertThatThrownBy(() -> compile.openArtifact(me.principal(), jobId))
                .isInstanceOf(ArtifactUnavailable.class)
                .hasMessageContaining("queued");
    }

    @Test
    @DisplayName("an expired download is refused even though the row still says done")
    void expiredDownloadRefused() throws Exception {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("txt", "download"));

        Path staging = Path.of(properties.stagingPath());
        Files.createDirectories(staging);
        Path file = staging.resolve("expired-" + jobId + ".txt");
        Files.writeString(file, "some prose");

        jdbc.sql("""
                UPDATE compile_job SET status = 'done', output_path = :path, output_filename = 'x.txt',
                       expires_at = now() - interval '1 hour' WHERE id = :id
                """).param("path", file.toString()).param("id", jobId).update();

        assertThatThrownBy(() -> compile.openArtifact(me.principal(), jobId))
                .isInstanceOf(ArtifactUnavailable.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("a path outside the configured roots is refused, however it got there")
    void pathTraversalRefused() {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("txt", "download"));

        // A corrupted or tampered row must not turn a download into an arbitrary file read.
        jdbc.sql("""
                UPDATE compile_job SET status = 'done', output_path = '/etc/passwd',
                       output_filename = 'passwd' WHERE id = :id
                """).param("id", jobId).update();

        assertThatThrownBy(() -> compile.openArtifact(me.principal(), jobId))
                .isInstanceOf(ArtifactUnavailable.class);
    }

    @Test
    @DisplayName("a finished export can be opened and reads back byte for byte")
    void finishedExportIsReadable() throws Exception {
        Actor me = actor();
        UUID jobId = compile.submit(me.principal(), me.projectId(), request("txt", "download"));

        Path staging = Path.of(properties.stagingPath());
        Files.createDirectories(staging);
        Path file = staging.resolve("ready-" + jobId + ".txt");
        Files.writeString(file, "The lamp had not been lit.");

        jdbc.sql("""
                UPDATE compile_job SET status = 'done', output_path = :path,
                       output_filename = 'novel.txt', output_bytes = :size WHERE id = :id
                """)
                .param("path", file.toString()).param("size", Files.size(file)).param("id", jobId)
                .update();

        var artifact = compile.openArtifact(me.principal(), jobId);
        assertThat(artifact.filename()).isEqualTo("novel.txt");
        assertThat(artifact.contentType()).startsWith("text/plain");
        try (var stream = artifact.stream()) {
            assertThat(new String(stream.readAllBytes())).isEqualTo("The lamp had not been lit.");
        }
    }
}

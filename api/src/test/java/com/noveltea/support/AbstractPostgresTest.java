package com.noveltea.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** Base for tests that need the real schema. Creates an isolated schema once per JVM. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
public abstract class AbstractPostgresTest {

    private static final String SCHEMA = "noveltea_test";

    static {
        String url = System.getenv().getOrDefault(
                "NOVELTEA_TEST_JDBC_URL", "jdbc:postgresql://localhost:5432/noveltea");
        String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        try (Connection c = DriverManager.getConnection(
                        base,
                        System.getenv().getOrDefault("NOVELTEA_TEST_DB_USER", "noveltea"),
                        System.getenv().getOrDefault("NOVELTEA_TEST_DB_PASSWORD", "noveltea"));
                Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot prepare test schema. Is Postgres running and reachable? " + e.getMessage(), e);
        }
    }

    /** Tables cleared between tests, children first. */
    private static final List<String> TABLES = List.of(
            "change_log", "collection_item", "collection", "custom_metadata_value",
            "custom_metadata_field", "compile_job", "compile_preset", "snapshot",
            "document", "project_invitation", "project_member", "binder_item",
            "taxonomy", "project", "device", "app_user");

    @Autowired protected JdbcClient jdbc;
    @Autowired protected DataSource dataSource;

    protected UUID userId;
    protected UUID projectId;
    protected UUID deviceA;
    protected UUID deviceB;

    @BeforeEach
    void resetAndSeed() {
        // Schema-qualified deliberately: an unqualified name could resolve through
        // search_path to the development tables in `public`.
        String qualified = TABLES.stream().map(t -> SCHEMA + "." + t).collect(java.util.stream.Collectors.joining(", "));
        jdbc.sql("TRUNCATE " + qualified + " RESTART IDENTITY CASCADE").update();

        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        deviceA = UUID.randomUUID();
        deviceB = UUID.randomUUID();

        jdbc.sql("INSERT INTO app_user (id, email) VALUES (:id, :email)")
                .param("id", userId).param("email", userId + "@example.com").update();
        jdbc.sql("INSERT INTO project (id, owner_id, title) VALUES (:id, :owner, 'Book')")
                .param("id", projectId).param("owner", userId).update();
        for (UUID device : List.of(deviceA, deviceB)) {
            jdbc.sql("INSERT INTO device (id, user_id, name, platform) VALUES (:id, :user, :name, 'web')")
                    .param("id", device).param("user", userId).param("name", "dev-" + device).update();
        }
    }

    /** Creates a binder_item + document pair and returns its id. */
    protected UUID seedDocument(String title, String orderKey, String contentText) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO binder_item (id, project_id, type, title, order_key, updated_by_device_id)
                VALUES (:id, :projectId, 'document', :title, :orderKey, :device)
                """)
                .param("id", id).param("projectId", projectId).param("title", title)
                .param("orderKey", orderKey).param("device", deviceA).update();
        jdbc.sql("""
                INSERT INTO document (id, content, updated_by_device_id)
                VALUES (:id, CAST(:content AS jsonb), :device)
                """)
                .param("id", id).param("content", doc(contentText)).param("device", deviceA).update();
        return id;
    }

    /** Minimal ProseMirror document JSON carrying one paragraph of text. */
    protected static String doc(String text) {
        return "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":"
                + "[{\"type\":\"text\",\"text\":\"" + text + "\"}]}]}";
    }

    /** Every piece of document text currently stored anywhere in the project. */
    protected List<String> allStoredText() {
        return jdbc.sql("""
                SELECT d.content::text FROM document d
                  JOIN binder_item b ON b.id = d.id
                 WHERE b.project_id = :projectId
                """)
                .param("projectId", projectId)
                .query(String.class)
                .list();
    }
}

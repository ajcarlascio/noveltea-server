package com.noveltea.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness and readiness, for container orchestration.
 *
 * <p>Readiness actually touches the database: an instance that cannot reach Postgres can
 * serve nothing useful, and reporting itself healthy would have a load balancer keep
 * sending it traffic. Neither endpoint reveals anything about the deployment.
 */
@RestController
@Tag(name = "Health", description = "Unauthenticated liveness and readiness, for container orchestration.")
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Liveness: the process is up. Never touches a dependency. */
    @Operation(
            summary = "Liveness",
            description = "The process is up. Never touches a dependency — this cannot fail "
                    + "as a proxy for the database being reachable; use /health/ready for that.",
            security = {})
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "up");
    }

    @Operation(
            summary = "Readiness",
            description = "Touches the database. 503 (not 500) when Postgres is unreachable, "
                    + "with no detail in the body — this endpoint needs no authentication and "
                    + "must not reveal anything about the deployment.",
            security = {})
    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok(Map.of("status", "ready"));
        } catch (Exception e) {
            // Deliberately no detail: this endpoint is reachable without authentication.
            return ResponseEntity.status(503).body(Map.of("status", "not-ready"));
        }
    }
}

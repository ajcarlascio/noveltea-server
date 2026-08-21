package com.noveltea.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the document as a whole: title, description, and the bearer-JWT security
 * scheme applied to every operation by default.
 *
 * <p>Nothing here is reachable itself — this only configures how springdoc renders
 * {@code /v3/api-docs}, both when served live and when the build-time plugin scrapes it
 * into {@code docs/api/openapi.yaml}. See {@link SecurityConfig} for why those endpoints
 * are off by default and how they are reached when turned on.
 */
@OpenAPIDefinition(
        info =
                @Info(
                        title = "NovelTea API",
                        version = "v1",
                        description =
                                """
                        Self-hosted, offline-first long-form writing app. Every client keeps a full \
                        local replica; this API is a synchronisation point and a compile engine, not \
                        where the work lives.

                        A few things about the shape of the responses that are easy to get wrong \
                        from the schema alone:

                        - **A resource the caller may not see returns `404`, never `403`** — a 403 \
                        would confirm it exists. Unauthenticated is `401`, so a client knows whether \
                        refreshing its token would help.
                        - **`POST /projects/{id}/sync` answers `200` even when some changes conflict.** \
                        A conflict is an ordinary outcome, reported per change in the response body \
                        (`applied` / `conflicts`), not an HTTP error. A `document` conflict never \
                        overwrites or merges — the server keeps its version and the client's is \
                        preserved as a sibling "conflict copy" (`conflictCopyId`); see \
                        `ConflictReason` for why an individual change was rejected.
                        - **`GET /projects/{id}/sync` can answer `resyncRequired: true`.** The \
                        client's cursor points into history the server can no longer explain (purged, \
                        or the project was restored from backup) and must discard it, rebuild from \
                        `GET /binder` plus documents, and resume at the returned `latestId`.
                        - **Commercial features answer `501`** with error code \
                        `unavailable_in_this_edition`. This build is Core: sharing, extra export \
                        formats and cloud destinations live in a separate paid module and are \
                        reported as an upgrade path, never a stack trace.
                        - **Compiling is asynchronous.** `POST /projects/{id}/compile` returns a job \
                        id immediately; poll `GET /compile-jobs/{id}` until its status is terminal, \
                        then `GET /compile-jobs/{id}/download`.
                        - **`POST /auth/password-reset` always answers `202`**, whether or not the \
                        address is registered — a different answer would make it an \
                        account-enumeration oracle.
                        """),
        security = @SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME),
        // Relative rather than the forked doc-generation host: the checked-in spec is
        // meant to describe whatever instance is serving it, not localhost:8099.
        servers = @Server(url = "/", description = "This server"))
@SecurityScheme(
        name = OpenApiConfig.BEARER_SCHEME,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description =
                "Access token from /auth/register, /auth/login, /auth/refresh or /auth/pair. "
                        + "15-minute lifetime by default (noveltea.auth.access-token-ttl).")
@Configuration
public class OpenApiConfig {

    /** Referenced by name from controllers that must opt out (the public auth routes). */
    public static final String BEARER_SCHEME = "bearerAuth";
}

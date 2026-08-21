plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.liquibase)
    // Boots the app in a forked JVM at build time and scrapes /v3/api-docs, so the
    // checked-in spec (docs/api/openapi.yaml) is generated, never hand-maintained.
    alias(libs.plugins.springdocOpenapiGradle)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Provides Nimbus JOSE for signing and verifying access tokens. Rolling our own
    // JWT handling would mean hand-writing crypto, which is never the right trade.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Jakarta Mail via Spring's JavaMailSender. No third-party dependency: an operator
    // points it at whatever SMTP they already run.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")
    // Apache-2.0. Serves the live spec (/v3/api-docs) and Swagger UI. Both are disabled
    // by default — see application.yml — because the spec is a full route/schema map and
    // a self-hosted instance facing the open internet should opt into publishing it.
    implementation(libs.springdoc.openapi.webmvc.ui)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform(libs.testcontainers.bom))
    // Sync correctness cannot be tested against H2: the tx_id / pg_snapshot_xmin
    // visibility gate and the concurrent-commit ordering case both need real
    // Postgres MVCC semantics. Tests run against a container, always.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // The Liquibase Gradle plugin runs the CLI in its own classpath, separate from
    // the application's, so these are declared again here.
    liquibaseRuntime("org.liquibase:liquibase-core")
    liquibaseRuntime("org.postgresql:postgresql")
    liquibaseRuntime(libs.picocli)
    liquibaseRuntime("org.yaml:snakeyaml")
}

liquibase {
    activities.register("main") {
        this.arguments = mapOf(
            // Liquibase records the changelog path as part of each changeset's identity.
            // These must match what Spring uses at startup ("classpath:db/changelog/..."),
            // or the same changeset is seen as two: Spring finds none applied and tries to
            // recreate a schema that already exists.
            "changelogFile" to "db/changelog/db.changelog-master.yaml",
            "searchPath" to file("src/main/resources").absolutePath,
            "url" to (project.findProperty("db.url") ?: "jdbc:postgresql://localhost:5432/noveltea"),
            "username" to (project.findProperty("db.username") ?: "noveltea"),
            "password" to (project.findProperty("db.password") ?: "noveltea")
        )
    }
    runList = "main"
}

// -----------------------------------------------------------------------------------
// OpenAPI spec generation (build-time)
// -----------------------------------------------------------------------------------
//
// `org.springdoc.openapi-gradle-plugin` boots the application in a forked JVM, requests
// /v3/api-docs from it, writes the response to a file, then stops it. This is what keeps
// docs/api/openapi.yaml from drifting: it comes from the same annotated controllers that
// serve traffic, not from anything hand-maintained.
//
// The forked app needs a JWT secret that passes AuthProperties' minimum-length check
// (TokenService requires >=32 decoded bytes; startup has no fallback, by design — see
// CLAUDE.md) and its own port so it never collides with a developer's `bootRun` on 8080.
// Neither value is sensitive: this process only ever answers its own scrape request on
// localhost and is torn down immediately after.
val openApiDocGenPort = 8099
val openApiDocGenJwtSecret = "b3BlbmFwaS1kb2MtZ2VuZXJhdGlvbi1vbmx5LW5vdC1hLXNlY3JldC0zMmIr"

openApi {
    apiDocsUrl.set("http://localhost:$openApiDocGenPort/v3/api-docs.yaml")
    outputDir.set(file("$rootDir/docs/api"))
    outputFileName.set("openapi.yaml")
    // Liquibase alone adds ~10s to startup (see CLAUDE.md); leave headroom.
    waitTimeInSeconds.set(60)
    customBootRun {
        args.set(listOf("--server.port=$openApiDocGenPort"))
        jvmArgs.set(listOf(
            "-Dnoveltea.auth.jwt-secret=$openApiDocGenJwtSecret",
            // The real app ships with the docs endpoints off (see application.yml); force
            // them on here regardless, so the endpoint this task scrapes actually exists.
            "-Dspringdoc.api-docs.enabled=true",
        ))
    }
}

// OpenApiSpecFreshnessTest compares the checked-in spec to the live handler mapping, so the
// spec is an input to the test task. Without this, Gradle considers `test` up to date after
// the spec alone changes and the guard silently does not run — which is exactly the state it
// exists to catch.
tasks.named<Test>("test") {
    inputs.file("$rootDir/docs/api/openapi.yaml")
        .withPropertyName("openApiSpec")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The spec is checked in (chosen over a build/ artifact so it can be diffed in review —
// a spec change with nothing to fail a test on is exactly the kind of silent drift this
// generation step exists to prevent). Wired into `build` so it can never go stale.
tasks.named("build") {
    dependsOn("generateOpenApiDocs")
}

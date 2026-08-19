plugins {
    alias(libs.plugins.springBoot)
    alias(libs.plugins.springDependencyManagement)
    alias(libs.plugins.liquibase)
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

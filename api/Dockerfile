# syntax=docker/dockerfile:1
#
# The Spring Boot API.
#
# Built from the repository root, not from api/ — the Gradle build is multi-project and
# the wrapper, version catalogue and settings all live a level up:
#
#   docker build -f api/Dockerfile -t noveltea-api .
#
# `bootJar`, deliberately, and never `build`: the root build wires `build` to
# `generateOpenApiDocs`, which boots the real application against a real Postgres to scrape
# its own spec. That is exactly right on a developer's machine and impossible inside an
# image build, where there is no database to boot against.

# Pinned to the BUILD platform, not the target. This stage produces a JAR, which is the
# same bytes on every architecture — so building it once natively is not an optimisation.
# The multi-arch publish emulates its arm64 leg with QEMU, and a JVM running Gradle under
# emulation is slow at best; the client repository lost a push to main to exactly this,
# with V8 rather than the JVM. The runtime stage below stays on the target platform.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Build definitions before sources, so editing a Java file does not re-resolve every
# dependency. `dependencies` is allowed to fail: it is a cache-warming step, not a gate,
# and a network hiccup here should not fail a build that would otherwise succeed.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY api/build.gradle.kts api/build.gradle.kts
RUN chmod +x gradlew \
    && (./gradlew --no-daemon :api:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true)

COPY api/src api/src
RUN ./gradlew --no-daemon --console=plain :api:bootJar

# ---------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine AS runtime

# Two volumes' worth of meaning, and only one of them is the author's work. Both are
# created here so the image runs correctly with neither mounted — a first `docker run`
# should not fail on a missing directory — and chowned so the unprivileged user can write.
ENV NOVELTEA_EXPORT_PATH=/var/lib/noveltea/exports \
    NOVELTEA_STAGING_PATH=/var/lib/noveltea/staging

RUN addgroup -S noveltea && adduser -S -G noveltea -h /var/lib/noveltea noveltea \
    && mkdir -p "$NOVELTEA_EXPORT_PATH" "$NOVELTEA_STAGING_PATH" \
    && chown -R noveltea:noveltea /var/lib/noveltea

WORKDIR /app
COPY --from=build /src/api/build/libs/*.jar /app/noveltea-api.jar

USER noveltea
EXPOSE 8080

# /health is liveness only. /health/ready touches the database, which is what a
# dependent container should actually wait on — see the compose file's condition.
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=5 \
    CMD wget -qO- http://127.0.0.1:8080/health/ready > /dev/null || exit 1

# Container memory, not the host's: without this the JVM sizes its heap against the whole
# machine and gets killed by the cgroup limit under load rather than throwing OutOfMemory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/noveltea-api.jar"]

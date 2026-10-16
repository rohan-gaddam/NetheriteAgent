# Multi-stage production image for the NetheriteAgent control plane.
#
# Stage 1 builds the fat jar with the identical toolchain used locally (Maven 3.9.9,
# Temurin JDK 21). Stage 2 is a minimal JRE runtime with a dedicated non-root user.
# The runtime has no Maven, no source, and no build cache — only the jar and curl
# (used by the container healthcheck).

# WS7: base images digest-pinned to the exact linux/amd64 manifests recorded
# 2026-10-06. Re-resolve when bumping toolchains: docker pull <tag> && docker
# image inspect --format '{{index .RepoDigests 0}}' <tag>.

# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21@sha256:3a4ab3276a087bf276f79cae96b1af04f53731bec53fb2e651aca79e4b10211e AS build
WORKDIR /src
COPY . .
RUN mvn -q -ntp -pl netherite-app -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --shell /usr/sbin/nologin --no-create-home app
WORKDIR /opt/netherite
COPY --from=build /src/netherite-app/target/netherite-app-0.1.0-SNAPSHOT.jar /opt/netherite/app.jar
COPY --from=build /src/netherite-app/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chown -R app:app /opt/netherite \
    && mkdir -p /tmp && chown app:app /tmp \
    && chmod 0755 /usr/local/bin/docker-entrypoint.sh
USER 10001
EXPOSE 8080

# Liveness probe: the process is alive (200) even when optional fail-closed
# dependencies (Redis coordination, OPA) are down. Readiness is reported by Spring
# itself at /actuator/health/readiness and is what the load balancer consumes.
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "curl -fs http://localhost:8080/actuator/health/liveness >/dev/null 2>&1 || exit 1"]

# Secrets are injected from file-based docker secrets (/run/secrets/*) by the
# entrypoint, never baked into the image or expanded through Compose interpolation.
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
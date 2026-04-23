# syntax=docker/dockerfile:1.7
#
# Multi-stage build: Maven for compile, distroless for runtime.
#
# JPMC-style conventions baked in:
#   - Base images as build args (central version bumps via CI)
#   - BuildKit cache mount for Maven deps (no bloated layers)
#   - Numeric UID 1000 instead of named 'nonroot' (stable across base image bumps)
#   - OCI + org-specific labels (app-id, cost center, traceability)
#   - Container-aware JVM sizing (MaxRAMPercentage — no OOMKill surprises)
#   - ZGC for low-pause GC on modern JDKs
#
# Why distroless: no shell, no package manager, tiny attack surface.
# If you need a shell for debugging, swap runtime to eclipse-temurin:21-jre-alpine.

# ---------- Base image args ----------
# In a bank, these would point at artifactory.internal/... and be pinned by digest.
ARG BUILDER_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=gcr.io/distroless/java21-debian12:nonroot

# ---------- Stage 1: build ----------
FROM ${BUILDER_IMAGE} AS build
WORKDIR /workspace

# Cache dependencies separately from source (faster rebuilds).
# BuildKit cache mount keeps ~/.m2 out of the image layers entirely.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp clean package -DskipTests && \
    java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---------- Stage 2: runtime ----------
FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app

# OCI + org labels — auditors look for these; cost allocation and ownership
# tracking depend on app-id. All fields are overridable at build time.
ARG GIT_SHA=unknown
ARG BUILD_DATE=unknown
ARG APP_VERSION=0.0.1-SNAPSHOT
ARG APP_ID=APP-EMS-UNASSIGNED
ARG LOB=platform-eng

LABEL org.opencontainers.image.title="ems" \
      org.opencontainers.image.description="Employee Management System" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.source="https://github.com/ems/ems" \
      org.opencontainers.image.vendor="EMS Platform Team" \
      com.ems.app-id="${APP_ID}" \
      com.ems.lob="${LOB}"

# Copy Spring Boot layers — most -> least cache-friendly order.
# dependencies/snapshot-dependencies rarely change; application changes every build.
# --chown=1000:1000 sets ownership up-front (distroless has no chown binary at runtime).
ARG EXTRACTED=/workspace/target/extracted
COPY --from=build --chown=1000:1000 ${EXTRACTED}/dependencies/          ./
COPY --from=build --chown=1000:1000 ${EXTRACTED}/spring-boot-loader/    ./
COPY --from=build --chown=1000:1000 ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build --chown=1000:1000 ${EXTRACTED}/application/           ./

# Numeric UID — stable across base image versions, works with restrictive
# Pod Security Standards that reject 'runAsUser: anyUID'.
USER 1000

EXPOSE 8080

# Container-aware JVM:
#   MaxRAMPercentage=75  -> heap grows to 75% of the pod memory limit, not host RAM
#   UseZGC               -> low-pause GC; great for request-latency-sensitive services
#   ExitOnOutOfMemoryError -> fail fast on OOM so k8s restarts the pod cleanly
# Override/append at runtime via JAVA_TOOL_OPTIONS in the k8s Deployment env.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

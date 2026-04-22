# Multi-stage build: Maven for compile, distroless for runtime.
# Why distroless: no shell, no package manager, tiny attack surface — a JPMC-friendly default.
# If you need a shell for debugging, swap runtime to eclipse-temurin:21-jre-alpine.

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Cache dependencies separately from source (faster rebuilds)
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests \
    && java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

# ---------- Stage 2: runtime ----------
FROM gcr.io/distroless/java21-debian12:nonroot AS runtime
WORKDIR /app

# Copy Spring Boot layers — most -> least cache-friendly order.
# dependencies/snapshot-dependencies rarely change; application changes every build.
ARG EXTRACTED=/workspace/target/extracted
COPY --from=build ${EXTRACTED}/dependencies/ ./
COPY --from=build ${EXTRACTED}/spring-boot-loader/ ./
COPY --from=build ${EXTRACTED}/snapshot-dependencies/ ./
COPY --from=build ${EXTRACTED}/application/ ./

USER nonroot
EXPOSE 8080

# JAVA_TOOL_OPTIONS can be appended at runtime (k8s env) for GC / heap tuning.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

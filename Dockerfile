# syntax=docker/dockerfile:1
#
# Orqix image: React SPA (Node) -> Spring Boot jar (Maven, JDK 21) -> Red Hat UBI9 OpenJDK 21 runtime.
# One deployable: the SPA is served by the backend from the classpath folder static/.
#
#   docker build -t orqix/orqix:dev .
#   docker build --build-arg VERSION=0.1.0 --build-arg REVISION=$(git rev-parse HEAD) -t registry.example/orqix:0.1.0 .
#
# The same image runs three ways, chosen by SPRING_PROFILES_ACTIVE: prod (pods, default in OpenShift), migrate (the
# pre-deployment migration Job) and dev (local Compose). Runs as any non-root UID in group 0 (OpenShift restricted SCC),
# with a read-only root filesystem and a writable /tmp mount.
#
# Base images (verified to exist 2026-10-08): node:24-alpine, eclipse-temurin:21-jdk,
# registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

ARG NODE_IMAGE=node:24-alpine
ARG JDK_IMAGE=eclipse-temurin:21-jdk
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.24

# ---------------------------------------------------------------------------------------------------------------------
# 1. Frontend: npm ci && npm run build  ->  /src/frontend/dist
# ---------------------------------------------------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS frontend
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
# The frontend imports shared fixtures from ../examples (mock layer, tests) and reads ../contracts; they are never
# copied into frontend/, so they must sit beside it exactly as in the repository.
COPY examples/ /src/examples/
COPY contracts/ /src/contracts/
# Brand palette (docs/build/S7-frontend.md section 8): wf (default) or orqix. docker build --build-arg ORQIX_BRAND=orqix
ARG ORQIX_BRAND=wf
RUN VITE_ORQIX_BRAND="$ORQIX_BRAND" npm run build:embedded

# ---------------------------------------------------------------------------------------------------------------------
# 2. Backend: the SPA is copied into the classpath static/ folder before packaging.
#    contracts/ and examples/ sit beside backend/ because the build reads shared fixtures and templates from them.
# ---------------------------------------------------------------------------------------------------------------------
FROM ${JDK_IMAGE} AS backend
WORKDIR /src
COPY backend/ ./backend/
COPY contracts/ ./contracts/
COPY examples/ ./examples/
COPY --from=frontend /src/frontend/dist/ ./backend/app/src/main/resources/static/
# Tests are CI stage 2 (`./mvnw verify`); the image build neither runs nor compiles them, so it depends on main sources only.
RUN --mount=type=cache,target=/root/.m2 \
    sh backend/mvnw -B -ntp -f backend/pom.xml -pl app -am package -DskipTests -Dmaven.test.skip=true \
    && cp backend/app/target/orqix-app.jar /orqix-app.jar

# ---------------------------------------------------------------------------------------------------------------------
# 3. Runtime
# ---------------------------------------------------------------------------------------------------------------------
FROM ${RUNTIME_IMAGE}

ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="orqix" \
      org.opencontainers.image.description="Orqix business workflow platform (R0)" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      io.openshift.expose-services="8080:http" \
      io.k8s.display-name="Orqix"

# Base-image packages with published security fixes newer than the pinned tag (image scan, 28 October 2026:
# CVE-2026-19445 and CVE-2026-19553 in python3/python3-libs). Drop this when the base tag already contains them.
USER 0
RUN microdnf -y update --nodocs --setopt=install_weak_deps=0 python3 python3-libs && microdnf clean all

WORKDIR /app
# Root-owned and read-only for every UID; the process never writes below /app.
COPY --from=backend --chown=0:0 --chmod=0444 /orqix-app.jar /app/orqix-app.jar

# Container-aware heap (75 % of the memory limit), crash on OOM so the pod restarts, no hsperfdata file in /tmp.
# Override or extend with JAVA_OPTS (for example -Dlogging.level.com.orqix=DEBUG).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:-UsePerfData -Djava.io.tmpdir=/tmp"

# Non-root by default; OpenShift replaces the UID with a namespace-range UID in group 0, which this image tolerates.
USER 185
EXPOSE 8080 8081
# Arguments after the image name reach Spring Boot (docker run image --spring.profiles.active=migrate).
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/orqix-app.jar \"$@\"", "orqix"]

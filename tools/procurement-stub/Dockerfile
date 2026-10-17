# syntax=docker/dockerfile:1
#
# Procurement stub image (development and E2E fixture only).
#
# The build context is the REPOSITORY ROOT, because the stub embeds the single shared contract
# examples/connections/procurement.connection.json:
#
#   docker build -f tools/procurement-stub/Dockerfile -t orqix/procurement-stub:dev .
#
# Which files enter the context is controlled by tools/procurement-stub/Dockerfile.dockerignore.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/tools/procurement-stub

# Dependency layer: changes only when the pom or the Maven wrapper change.
COPY tools/procurement-stub/.mvn .mvn
COPY tools/procurement-stub/mvnw tools/procurement-stub/pom.xml ./
COPY examples/connections/procurement.connection.json /src/examples/connections/procurement.connection.json
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -q dependency:resolve

COPY tools/procurement-stub/src src
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -B -ntp -DskipTests package \
 && cp target/procurement-stub.jar /procurement-stub.jar

FROM eclipse-temurin:21-jre-alpine
# Fixed non-root user; the image also runs under an arbitrary UID (the jar is world-readable, nothing is written).
RUN addgroup -S -g 10001 stub && adduser -S -u 10001 -G stub -H -s /sbin/nologin stub
COPY --from=build /procurement-stub.jar /app/procurement-stub.jar
USER 10001:10001
# Port inside the Compose network; the host maps it (default 8095).
ENV SERVER_PORT=8090 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=5 \
  CMD wget -q -O /dev/null "http://127.0.0.1:${SERVER_PORT}/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/procurement-stub.jar"]

# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble AS mod-builder

WORKDIR /workspace/server
COPY server/ ./

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :fabric:build \
    && artifact="$(find fabric/build/libs -maxdepth 1 -type f -name 'boxloom-*.jar' ! -name '*-sources.jar' -print -quit)" \
    && test -n "${artifact}" \
    && cp "${artifact}" /tmp/boxloom.jar

FROM itzg/minecraft-server:2026.5.4-java25

COPY --from=mod-builder --chown=1000:1000 /tmp/boxloom.jar /boxloom/boxloom.jar

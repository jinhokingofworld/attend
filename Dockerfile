# syntax=docker/dockerfile:1

# The build and runtime both use Java 21. Only the bootJar crosses the stage
# boundary, so Gradle caches, source files and compiler tools are absent at runtime.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod 0755 gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew bootJar migrationBootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runtime-base
RUN apk add --no-cache curl \
    && addgroup -S attend \
    && adduser -S -G attend -h /app attend
WORKDIR /app
USER attend

FROM runtime-base AS runtime
COPY --from=build --chown=attend:attend /workspace/build/libs/attend-app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS migration
COPY --from=build --chown=attend:attend /workspace/build/libs/attend-migration.jar migration.jar
ENTRYPOINT ["java", "-jar", "/app/migration.jar"]

# Stage 1: Build
FROM gradle:8.8-jdk17 AS builder
WORKDIR /build
COPY backend/settings.gradle.kts ./settings.gradle.kts
COPY backend/build.gradle.kts   ./build.gradle.kts
RUN gradle dependencies --no-daemon --quiet || true
COPY backend/src ./src
RUN gradle buildFatJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /build/build/libs/construction-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

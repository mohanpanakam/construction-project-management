# Stage 1: Build
FROM gradle:8.10.2-jdk21 AS builder
WORKDIR /build
COPY backend/settings.gradle.kts ./settings.gradle.kts
COPY backend/build.gradle.kts   ./build.gradle.kts
RUN gradle dependencies --no-daemon --quiet || true
COPY backend/src ./src
RUN gradle buildFatJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# Tesseract OCR (free, local, offline) — used by Tess4J for image receipt extraction.
# tesseract-ocr-eng ships the English trained data; libleptonica provides image processing.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng libleptonica-dev \
    && rm -rf /var/lib/apt/lists/*
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

COPY --from=builder /build/build/libs/construction-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

# Multi-Module Gradle Build Dockerfile
# This Dockerfile builds the medscribe-platform multi-module project

# ============================================
# Stage 1: Builder - Build all modules
# ============================================
FROM gradle:8.12.1-jdk21 AS builder
WORKDIR /workspace

# Copy root project configuration
COPY settings.gradle settings.gradle
COPY build.gradle build.gradle
COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY gradle gradle

# Copy all library modules (needed for building medscribe-ai)
COPY elioo-aws-common elioo-aws-common
COPY elioo-aws-textract elioo-aws-textract
COPY elioo-aws-comprehend-medical elioo-aws-comprehend-medical
COPY elioo-aws-bedrock elioo-aws-bedrock
COPY elioo-aws-spring-boot-starter elioo-aws-spring-boot-starter

# Copy GCP library modules (added for translation and vision support)
COPY elioo-gcp-common elioo-gcp-common
COPY elioo-gcp-translate elioo-gcp-translate
COPY elioo-gcp-vision elioo-gcp-vision
COPY elioo-gcp-spring-boot-starter elioo-gcp-spring-boot-starter

# Copy main application module
COPY medscribe-ai medscribe-ai

# GCP credentials are NOT copied into the image for security
# They will be mounted as a volume at runtime from the server
# See deploy.sh for volume mount configuration

# Build the entire multi-module project
# This builds all modules and creates the executable JAR in medscribe-ai/build/libs/
RUN --mount=type=cache,target=/root/.gradle gradle build -x test

# Extract the Spring Boot JAR for layered approach
RUN mkdir -p build/dependency && \
    (cd build/dependency; jar -xf /workspace/medscribe-ai/build/libs/medscribe-ai-*-SNAPSHOT.jar)

# ============================================
# Stage 2: Runtime - Minimal JRE image
# ============================================
FROM eclipse-temurin:21-jre
WORKDIR /workspace

# Create log directory for application logs
RUN mkdir -p /var/log/pfh/

# Copy extracted layers from builder
ARG DEPENDENCY=/workspace/build/dependency
COPY --from=builder ${DEPENDENCY}/BOOT-INF/lib /workspace/app/lib
COPY --from=builder ${DEPENDENCY}/META-INF /workspace/app/META-INF
COPY --from=builder ${DEPENDENCY}/BOOT-INF/classes /workspace/app

# NOTE: GCP credentials are NOT copied into the image
# They will be mounted at runtime via volume mount: -v /path/to/creds:/secrets/gcp-credentials.json
# This approach is more secure and allows credential rotation without rebuilding the image

# Expose application port (default: 8086)
EXPOSE 8086

# Health check (optional - adjust path if needed)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8086/actuator/health || exit 1

# Run the application using the main class directly
ENTRYPOINT ["java", \
    "-cp", "app:app/lib/*", \
    "com.elioo.healthcare.MedscribeAiApplication"]

# Multi-stage Dockerfile for Unicity Faucet Server

# Stage 1: Build the application
# Use JDK 17 since unicity-nostr-sdk requires it
FROM gradle:8.5-jdk17 AS builder

WORKDIR /build

# Copy the unicity-nostr-sdk dependency first
COPY unicity-nostr-sdk /unicity-nostr-sdk

# Copy faucet Gradle files and libs (for caching)
COPY faucet/build.gradle.kts faucet/settings.gradle.kts ./
COPY faucet/libs ./libs

# Copy faucet source code
COPY faucet/src ./src

# Build the application (skip tests for faster builds)
RUN gradle build -x test --no-daemon

# Stage 2: Runtime image
FROM eclipse-temurin:11-jre

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /build/build/libs/*.jar app.jar

# Create data directory
RUN mkdir -p /app/data

# Set environment variables
ENV DATA_DIR=/app/data \
    PORT=8080

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run the server
CMD ["java", "-jar", "app.jar"]

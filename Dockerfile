

################################################
# Run stage

# Use Eclipse Temurin 25 LTS as base image (modern JVM)
FROM eclipse-temurin:25-jre-alpine

LABEL org.opencontainers.image.source="https://github.com/covia-ai/covia" \
      org.opencontainers.image.description="Covia federated AI orchestration venue" \
      org.opencontainers.image.licenses="EPL-2.0"

# Set working directory
WORKDIR /app

# Install necessary packages for Alpine
RUN apk add --no-cache \
    curl \
    && rm -rf /var/cache/apk/*

# Create non-root user for security
RUN addgroup -g 1001 -S appgroup && \
    adduser -u 1001 -S appuser -G appgroup

# Copy the built JAR file from the venue target directory
COPY venue/target/covia.jar /app/covia.jar

# Copy any additional resources if needed
COPY venue/src/main/resources/ /app/resources/

# Explicit, ephemeral local-test configuration. The default launch remains
# read-only; this config is selected only when its path is passed as an image
# argument and should be published on the host loopback interface only.
COPY docker-local.json /app/config/docker-local.json

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose the port the app runs on
EXPOSE 8080

# Set JVM options optimized for Cloud Run
# --sun-misc-unsafe-memory-access: JDK 24+ warns once when protobuf-java
# touches Unsafe (JEP 471); that warning is for the library, not the operator.
ENV JAVA_OPTS="-XX:+UseContainerSupport \
                --sun-misc-unsafe-memory-access=allow \
                -XX:MaxRAMPercentage=75.0 \
                -XX:+UseG1GC \
                -XX:+UseStringDeduplication \
                -Djava.security.egd=file:/dev/./urandom \
                -Dfile.encoding=UTF-8"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Run the application. Additional Docker arguments are forwarded to MainVenue,
# so `docker run IMAGE /app/config/docker-local.json` selects a config without
# replacing the Java command.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/covia.jar \"$@\"", "--"]

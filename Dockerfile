# Use Eclipse Temurin 21 LTS as base image (modern JVM)
FROM eclipse-temurin:21-jre-alpine

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

# Change ownership to non-root user
RUN chown -R appuser:appgroup /app

# Switch to non-root user
USER appuser

# Expose the port the app runs on
EXPOSE 8080

# Set JVM options optimized for Cloud Run
ENV JAVA_OPTS="-XX:+UseContainerSupport \
                -XX:MaxRAMPercentage=75.0 \
                -XX:+UseG1GC \
                -XX:+UseStringDeduplication \
                -Djava.security.egd=file:/dev/./urandom \
                -Dfile.encoding=UTF-8"

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/ || exit 1

# Run the application
CMD ["sh", "-c", "java $JAVA_OPTS -jar covia.jar"] 
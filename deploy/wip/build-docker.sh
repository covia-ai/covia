#!/bin/bash

# Build script for Covia Docker image
# This script builds the Maven project and creates a Docker image ready for Google Cloud Run

set -e

echo "🚀 Building Covia Docker image for Google Cloud Run..."

# Check if Maven is available
if ! command -v mvn &> /dev/null; then
    echo "❌ Maven is not installed or not in PATH"
    echo "Please install Maven first: https://maven.apache.org/install.html"
    exit 1
fi

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed or not in PATH"
    echo "Please install Docker first: https://docs.docker.com/get-docker/"
    exit 1
fi

# Build the Maven project
echo "📦 Building Maven project..."
cd venue
mvn clean install -DskipTests
cd ..

# Check if the JAR file was created
if [ ! -f "venue/target/covia.jar" ]; then
    echo "❌ Failed to build covia.jar"
    echo "Check the Maven build output for errors"
    exit 1
fi

echo "✅ Maven build completed successfully"

# Build the Docker image
echo "🐳 Building Docker image..."
docker build -t covia:latest .

echo "✅ Docker image built successfully!"
echo ""
echo "📋 Next steps:"
echo "1. Test locally: docker run -p 8080:8080 covia:latest"
echo "2. Tag for GCR: docker tag covia:latest gcr.io/YOUR_PROJECT_ID/covia:latest"
echo "3. Push to GCR: docker push gcr.io/YOUR_PROJECT_ID/covia:latest"
echo "4. Deploy to Cloud Run: gcloud run deploy covia --image gcr.io/YOUR_PROJECT_ID/covia:latest --platform managed"
echo ""
echo "🎯 Image is ready for Google Cloud Run deployment!" 
# Deploying Covia to Google Cloud Run

This guide explains how to deploy the Covia application to Google Cloud Run. **We recommend using GitHub Actions for automated deployment** (see [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) for the automated approach).

## 🚀 **Recommended: GitHub Actions (Automated)**

For production deployments, use the included GitHub Actions workflows:

1. **Automatic testing** on every push and pull request
2. **Automatic building** and deployment on main/master branch pushes
3. **Zero manual intervention** required after initial setup

See [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) for complete setup instructions.

## 🔧 **Alternative: Manual Deployment**

If you prefer manual deployment or need to deploy from your local machine:

### Prerequisites

- [Google Cloud SDK](https://cloud.google.com/sdk/docs/install) installed and configured
- [Docker](https://docs.docker.com/get-docker/) installed
- [Maven](https://maven.apache.org/install.html) installed
- A Google Cloud project with billing enabled

### Quick Start

```bash
# Make the build script executable
chmod +x build-docker.sh

# Build the Docker image
./build-docker.sh

# Test locally
docker run -p 8080:8080 covia:latest
```

### Deploy to Google Cloud Run

```bash
# Set your project ID
export PROJECT_ID="your-project-id"

# Configure Docker to use gcloud as a credential helper
gcloud auth configure-docker

# Tag the image for Google Container Registry
docker tag covia:latest gcr.io/$PROJECT_ID/covia:latest

# Push the image to GCR
docker push gcr.io/$PROJECT_ID/covia:latest

# Deploy to Cloud Run
gcloud run deploy covia \
  --image gcr.io/$PROJECT_ID/covia:latest \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --memory 2Gi \
  --cpu 2 \
  --port 8080
```

## Configuration Options

### Environment Variables

The application supports the following environment variables:

- `PORT`: The port to listen on (default: 8080)
- `JAVA_OPTS`: JVM options for optimization

### Resource Limits

The Cloud Run service is configured with:
- **CPU**: 1-2 vCPU
- **Memory**: 1-2 GiB
- **Concurrency**: 80 requests per container
- **Timeout**: 300 seconds

### Scaling

- **Min Scale**: 0 (scales to zero when not in use)
- **Max Scale**: 100 instances

## Health Checks

The application includes:
- **Liveness Probe**: Checks if the application is running
- **Readiness Probe**: Checks if the application is ready to receive traffic
- **Health Check**: Docker-level health check using curl

## Security Features

- Non-root user execution
- Minimal Alpine Linux base image
- Resource limits and timeouts
- Secure JVM options

## Monitoring and Logging

### View Logs

```bash
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=covia" --limit 50
```

### Monitor Performance

```bash
gcloud run services describe covia --region us-central1
```

## Troubleshooting

### Common Issues

1. **Port Binding**: Ensure the application listens on the port specified by the `PORT` environment variable
2. **Memory Issues**: Adjust memory limits if the application crashes
3. **Startup Time**: The application may take 10-30 seconds to start up

### Debug Commands

```bash
# Check container logs
docker logs <container_id>

# Execute commands in running container
docker exec -it <container_id> /bin/sh

# Check resource usage
docker stats <container_id>
```

### Performance Tuning

The JVM is configured with:
- G1 Garbage Collector for better performance
- Container-aware memory settings
- String deduplication for memory efficiency
- Secure random number generation

## Cost Optimization

- **Scale to Zero**: The service scales to zero when not in use
- **Resource Limits**: Set appropriate CPU and memory limits
- **Region Selection**: Choose a region close to your users

## Next Steps

After deployment:
1. Set up custom domain (if needed)
2. Configure monitoring and alerting
3. **Set up CI/CD pipeline** (use GitHub Actions for automated deployments)
4. Configure backup and disaster recovery

## Support

For issues with:
- **Application**: Check the application logs
- **Infrastructure**: Check Cloud Run service logs
- **Deployment**: Verify Docker image and configuration
- **GitHub Actions**: See [GITHUB_ACTIONS_SETUP.md](GITHUB_ACTIONS_SETUP.md) 
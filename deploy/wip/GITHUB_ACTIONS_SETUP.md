# GitHub Actions Setup for Google Cloud Run

This guide explains how to set up GitHub Actions to automatically build and deploy your Covia application to Google Cloud Run.

## Overview

The GitHub Actions workflow will:
1. **Test**: Run all tests on every push and pull request
2. **Build**: Create a Docker image with the latest code
3. **Deploy**: Automatically deploy to Google Cloud Run on main/master branch pushes

## Prerequisites

- [Google Cloud Project](https://console.cloud.google.com/) with billing enabled
- [Cloud Run API](https://console.cloud.google.com/apis/library/run.googleapis.com) enabled
- [Container Registry API](https://console.cloud.google.com/apis/library/containerregistry.googleapis.com) enabled
- [Cloud Build API](https://console.cloud.google.com/apis/library/cloudbuild.googleapis.com) enabled

## Step 1: Create a Service Account

### 1.1 Create the Service Account

```bash
# Set your project ID
export PROJECT_ID="your-project-id"

# Create service account
gcloud iam service-accounts create github-actions \
    --description="Service account for GitHub Actions" \
    --display-name="GitHub Actions"

# Get the service account email
export SA_EMAIL="github-actions@$PROJECT_ID.iam.gserviceaccount.com"
```

### 1.2 Grant Required Permissions

```bash
# Grant Cloud Run Admin role
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/run.admin"

# Grant Service Account User role
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/iam.serviceAccountUser"

# Grant Storage Admin role (for Container Registry)
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/storage.admin"

# Grant Cloud Build Editor role
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/cloudbuild.builds.editor"
```

### 1.3 Create and Download the Key

```bash
# Create and download the key
gcloud iam service-accounts keys create ~/github-actions-key.json \
    --iam-account=$SA_EMAIL

# Display the key (copy this for GitHub Secrets)
cat ~/github-actions-key.json
```

## Step 2: Configure GitHub Secrets

In your GitHub repository, go to **Settings** → **Secrets and variables** → **Actions** and add:

### Required Secrets

| Secret Name | Value | Description |
|-------------|-------|-------------|
| `GCP_PROJECT_ID` | `your-project-id` | Your Google Cloud Project ID |
| `GCP_SA_KEY` | `{...}` | The entire JSON content from the service account key |

### How to Add Secrets

1. Go to your repository on GitHub
2. Click **Settings** tab
3. Click **Secrets and variables** → **Actions**
4. Click **New repository secret**
5. Add each secret with the exact names above

## Step 3: Workflow Files

The repository now includes these workflow files:

- **`.github/workflows/test.yml`**: Runs tests on all pushes and PRs
- **`.github/workflows/deploy.yml`**: Builds and deploys on main/master branch pushes

## Step 4: Test the Setup

### 4.1 Push to Main Branch

```bash
git add .
git commit -m "Add GitHub Actions workflows"
git push origin main
```

### 4.2 Check GitHub Actions

1. Go to your repository on GitHub
2. Click **Actions** tab
3. You should see the workflow running

### 4.3 Verify Deployment

```bash
# List Cloud Run services
gcloud run services list --region=us-central1

# Get service details
gcloud run services describe covia --region=us-central1
```

## Workflow Details

### Test Job
- Runs on every push and pull request
- Sets up JDK 21 with Maven caching
- Runs all tests
- Builds the Maven project

### Build and Deploy Job
- Only runs on main/master branch pushes
- Requires test job to pass first
- Builds Docker image with GitHub Actions cache
- Pushes to Google Container Registry
- Deploys to Cloud Run with optimized settings

## Configuration Options

### Environment Variables

You can customize these in the workflow:

```yaml
env:
  PROJECT_ID: ${{ secrets.GCP_PROJECT_ID }}
  REGION: us-central1          # Change deployment region
  SERVICE_NAME: covia          # Change service name
```

### Cloud Run Settings

Current deployment settings:

```bash
--allow-unauthenticated    # Public access
--memory=2Gi               # Memory limit
--cpu=2                    # CPU limit
--port=8080                # Application port
--max-instances=100        # Maximum scaling
--timeout=300s             # Request timeout
--concurrency=80           # Requests per container
```

## Troubleshooting

### Common Issues

1. **Permission Denied**: Ensure service account has all required roles
2. **Build Failures**: Check Maven build logs in GitHub Actions
3. **Deployment Failures**: Verify Cloud Run API is enabled
4. **Authentication Issues**: Ensure GCP_SA_KEY secret is properly set

### Debug Commands

```bash
# Check service account permissions
gcloud projects get-iam-policy $PROJECT_ID \
    --flatten="bindings[].members" \
    --filter="bindings.members:$SA_EMAIL" \
    --format="table(bindings.role)"

# Check Cloud Run services
gcloud run services list --region=us-central1

# View service logs
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=covia" --limit 50
```

### Workflow Debugging

1. **Check Actions Tab**: View detailed logs for each step
2. **Re-run Jobs**: Use "Re-run jobs" button for failed workflows
3. **Manual Trigger**: Use "workflow_dispatch" to manually trigger workflows

## Security Best Practices

- ✅ Service account has minimal required permissions
- ✅ Secrets are encrypted in GitHub
- ✅ Non-root user in Docker container
- ✅ Resource limits prevent abuse
- ✅ Automatic scaling with limits

## Cost Optimization

- **Scale to Zero**: Services scale down when not in use
- **Resource Limits**: CPU and memory are capped
- **Build Caching**: GitHub Actions cache reduces build time
- **Efficient Base Image**: Alpine Linux reduces image size

## Next Steps

After successful setup:

1. **Custom Domain**: Configure custom domain for your service
2. **Monitoring**: Set up Cloud Monitoring and alerting
3. **CI/CD Pipeline**: The pipeline is now fully automated
4. **Environment Variables**: Add any application-specific environment variables
5. **Secrets Management**: Use Cloud Secret Manager for sensitive data

## Support

For issues:

1. **GitHub Actions**: Check the Actions tab for detailed logs
2. **Cloud Run**: Use `gcloud run services describe` for service status
3. **Permissions**: Verify service account has required roles
4. **API Status**: Check [Google Cloud Status](https://status.cloud.google.com/) 
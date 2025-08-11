#!/bin/bash

# GitHub Actions Setup Helper Script
# This script helps you set up the required Google Cloud resources for GitHub Actions

set -e

echo "🚀 GitHub Actions Setup Helper for Covia"
echo "========================================"
echo ""

# Check if gcloud is installed
if ! command -v gcloud &> /dev/null; then
    echo "❌ Google Cloud SDK is not installed or not in PATH"
    echo "Please install it first: https://cloud.google.com/sdk/docs/install"
    exit 1
fi

# Check if user is authenticated
if ! gcloud auth list --filter=status:ACTIVE --format="value(account)" | grep -q .; then
    echo "❌ You are not authenticated with Google Cloud"
    echo "Please run: gcloud auth login"
    exit 1
fi

echo "✅ Google Cloud SDK is installed and authenticated"
echo ""

# Get project ID
echo "Please enter your Google Cloud Project ID:"
read -p "Project ID: " PROJECT_ID

if [ -z "$PROJECT_ID" ]; then
    echo "❌ Project ID cannot be empty"
    exit 1
fi

# Set the project
echo "Setting project to: $PROJECT_ID"
gcloud config set project $PROJECT_ID

echo ""
echo "🔧 Setting up required APIs..."

# Enable required APIs
echo "Enabling Cloud Run API..."
gcloud services enable run.googleapis.com

echo "Enabling Container Registry API..."
gcloud services enable containerregistry.googleapis.com

echo "Enabling Cloud Build API..."
gcloud services enable cloudbuild.googleapis.com

echo ""
echo "👤 Creating service account..."

# Create service account
SA_NAME="github-actions"
SA_EMAIL="$SA_NAME@$PROJECT_ID.iam.gserviceaccount.com"

# Check if service account already exists
if gcloud iam service-accounts describe $SA_EMAIL &>/dev/null; then
    echo "Service account already exists, skipping creation..."
else
    gcloud iam service-accounts create $SA_NAME \
        --description="Service account for GitHub Actions" \
        --display-name="GitHub Actions"
fi

echo ""
echo "🔐 Granting required permissions..."

# Grant permissions
echo "Granting Cloud Run Admin role..."
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/run.admin"

echo "Granting Service Account User role..."
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/iam.serviceAccountUser"

echo "Granting Storage Admin role..."
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/storage.admin"

echo "Granting Cloud Build Editor role..."
gcloud projects add-iam-policy-binding $PROJECT_ID \
    --member="serviceAccount:$SA_EMAIL" \
    --role="roles/cloudbuild.builds.editor"

echo ""
echo "🔑 Creating service account key..."

# Create key file
KEY_FILE="github-actions-key.json"
gcloud iam service-accounts keys create $KEY_FILE \
    --iam-account=$SA_EMAIL

echo ""
echo "✅ Setup complete!"
echo ""
echo "📋 Next steps:"
echo "1. Copy the contents of '$KEY_FILE' to GitHub Secrets as 'GCP_SA_KEY'"
echo "2. Add '$PROJECT_ID' to GitHub Secrets as 'GCP_PROJECT_ID'"
echo "3. Push your code to trigger the GitHub Actions workflow"
echo ""
echo "🔒 GitHub Secrets to add:"
echo "   GCP_PROJECT_ID: $PROJECT_ID"
echo "   GCP_SA_KEY: [contents of $KEY_FILE]"
echo ""
echo "📖 For detailed instructions, see: GITHUB_ACTIONS_SETUP.md"
echo ""
echo "⚠️  Keep the key file secure and delete it after adding to GitHub Secrets"
echo "   rm $KEY_FILE" 
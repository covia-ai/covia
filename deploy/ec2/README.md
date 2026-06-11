# EC2 Deployment

Docker-based deployment for `venue-3.covia.ai` (`13.213.76.110`).

## One-Time EC2 Setup

SSH into the instance and run:

```bash
# Install Docker
sudo yum install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user

# Log out and back in for group change to take effect
exit

# Stop existing systemd service
sudo systemctl disable --now covia-venue.service

# Create data directory
mkdir -p /home/ec2-user/covia-data

# Log in to GHCR (use a GitHub PAT with read:packages scope)
docker login ghcr.io -u <github-username>
```

## GitHub Secrets Required

| Secret | Value |
|--------|-------|
| `EC2_SSH_KEY` | Contents of `covia-venue-key.pem` |
| `EC2_HOST` | `13.213.76.110` |
| `EC2_USER` | `ec2-user` |

## Manual Deploy

To deploy manually on the EC2 instance:

```bash
docker pull ghcr.io/covia-ai/covia:latest
docker stop covia-venue && docker rm covia-venue
docker run -d \
  --name covia-venue \
  -p 8080:8080 \
  -v /home/ec2-user/covia-data:/data \
  --restart unless-stopped \
  ghcr.io/covia-ai/covia:latest
```

Or using docker compose:

```bash
cd /home/ec2-user
# Copy docker-compose.yml to this directory
docker compose pull
docker compose up -d
```

## Verification

```bash
docker ps                        # container running
docker logs covia-venue          # startup logs
curl http://localhost:8080/      # venue response
docker inspect --format='{{.State.Health.Status}}' covia-venue  # healthy
```

## Architecture

```
Internet → nginx (443 TLS) → localhost:8080 → Docker container (covia-venue)
```

Nginx handles TLS termination and proxies to the container on port 8080.

# EC2 Deployment

Docker-based deployment for `venue-3.covia.ai` on AWS.

## One-Time EC2 Setup

Open a shell on the instance with SSM Session Manager (port 22 is not used;
see *Access* below) and run:

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
```

## How deploys reach the host

No SSH and no stored keys (security#12). `deploy-ec2.yml`:

1. assumes the IAM role `covia-deploy-venue-3` through GitHub OIDC. The role
   trusts only `repo:covia-ai/covia:ref:refs/heads/develop` and may only run
   `AWS-RunShellScript` on this instance and read the results;
2. sends [`deploy.sh`](deploy.sh) through SSM Run Command, where it runs as
   root, and fails the job unless the command finishes with `Success`.

The image on GHCR is public, so the host needs no registry login. The
instance role needs `AmazonSSMManagedInstanceCore` and the SSM agent
(preinstalled on Amazon Linux 2023).

## Access

Open an admin shell without SSH (needs the Session Manager plugin locally):

```bash
aws ssm start-session --region us-east-1 --target i-0ba252844d8787529
```

## Manual Deploy

Run the deploy workflow from the Actions tab (*Deploy to EC2*, `workflow_dispatch`), or in an SSM
session on the instance:

```bash
docker pull ghcr.io/covia-ai/covia:latest
docker stop covia-venue && docker rm covia-venue
docker run -d \
  --name covia-venue \
  -p 127.0.0.1:8080:8080 \
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

The server block must forward the client address, or every caller shares one
rate-limit bucket and one authentication budget at the venue (covia#539):

```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

The venue side is set by the deploy workflow: it reconciles
`"trustedProxies": ["loopback", "172.16.0.0/12"]` into the persisted config,
since the container sees nginx through the Docker bridge, not as loopback.

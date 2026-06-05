# Azure VM Deployment

Docker-based deployment for `venue-4.covia.ai` on Azure.

## Prerequisites

- Azure CLI installed and logged in (`az login`)
- Active Azure subscription
- DNS access to create A record for `venue-4.covia.ai`

## Provisioning

Run the setup script to create all Azure resources:

```bash
chmod +x deploy/azure/setup.sh
./deploy/azure/setup.sh
```

This creates: Resource Group, VNet, NSG (ports 22/80/443), Static IP, VM (Standard_B2s_v2 x86, Ubuntu 24.04), Docker, nginx, and Let's Encrypt TLS.

The script will pause before the nginx/TLS step and ask you to configure DNS first.

## Config File

SSH into the VM and create the venue config:

```bash
ssh azureuser@<public-ip>

cat > ~/covia-data/config.json << 'EOF'
{
  "venues": [
    {
      "name": "Covia Venue (Azure)",
      "hostname": "venue-4.covia.ai",
      "store": "/data/venue.etch",
      "mcp": {}
    }
  ]
}
EOF

# Fix permissions for container user (UID 1001)
sudo chown -R 1001:1001 ~/covia-data
```

## GitHub Secrets Required

Add these to the `covia-ai/covia` repository settings:

| Secret | Value |
|--------|-------|
| `AZURE_VM_HOST` | Static public IP from setup script |
| `AZURE_VM_USER` | `azureuser` |
| `AZURE_VM_SSH_KEY` | Contents of the SSH private key |

## Manual Deploy

```bash
ssh azureuser@<public-ip>

# On VM:
echo $GHCR_TOKEN | docker login ghcr.io -u github --password-stdin

docker pull ghcr.io/covia-ai/covia:latest
docker stop covia-venue 2>/dev/null || true
docker rm covia-venue 2>/dev/null || true

docker run -d \
  --name covia-venue \
  -p 8080:8080 \
  -v /home/azureuser/covia-data:/data \
  --restart unless-stopped \
  --health-cmd='curl -f --max-time 3 http://localhost:8080/' \
  --health-interval=30s \
  --health-timeout=5s \
  --health-start-period=15s \
  --health-retries=3 \
  ghcr.io/covia-ai/covia:latest \
  java -Xmx2g -XX:+UseContainerSupport -XX:+UseG1GC \
  -XX:+UseStringDeduplication -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 -jar covia.jar /data/config.json
```

## Verification

```bash
docker ps
docker logs covia-venue
curl http://localhost:8080/
docker inspect --format='{{.State.Health.Status}}' covia-venue

# External
curl https://venue-4.covia.ai/
curl https://venue-4.covia.ai/.well-known/did.json
curl https://venue-4.covia.ai/swagger
```

## Architecture

```
Internet → nginx (443 TLS, Let's Encrypt) → localhost:8080 → Docker container (covia-venue)
```

## Azure Resources Created

| Resource | Name |
|----------|------|
| Resource Group | `covia-venue-rg` |
| Virtual Network | `covia-vnet` |
| Subnet | `covia-subnet` |
| NSG | `covia-venue-nsg` |
| Public IP | `covia-venue-ip` |
| NIC | `covia-venue-nic` |
| VM | `covia-venue-vm` |

## Teardown

To delete all Azure resources:

```bash
az group delete --name covia-venue-rg --yes --no-wait
```

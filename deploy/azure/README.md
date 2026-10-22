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

## Automated Deploys (no SSH)

`.github/workflows/deploy-azure.yml` deploys after every successful image
publish from `develop`, or on manual dispatch. It runs
[`deploy.sh`](deploy.sh) on the VM through `az vm run-command` (security#12):

- The job signs in with `azure/login` over GitHub OIDC as the user-assigned
  managed identity `covia-deploy-venue-4` (in `covia-venue-rg`). Its
  federated credential trusts only `repo:covia-ai/covia:ref:refs/heads/develop`.
- The identity holds one custom role, "Covia VM Run Command"
  (`virtualMachines/read` and `virtualMachines/runCommand/action`),
  assigned on `covia-venue-vm` only.
- No SSH key, client secret or registry token is stored: the image on GHCR
  is public.
- run-command does not return the script's exit code, so the job checks for
  a `COVIA_DEPLOY_RESULT=ok` line. Only the last 4 KB of output come back.

## Manual Deploy

Admin access is through run-command (port 22 is closed):

```bash
az vm run-command invoke -g covia-venue-rg -n covia-venue-vm \
  --command-id RunShellScript --scripts @deploy/azure/deploy.sh \
  --query 'value[0].message' -o tsv
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

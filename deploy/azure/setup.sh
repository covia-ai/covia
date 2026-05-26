#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# Covia Venue — Azure VM Provisioning Script
#
# Creates all Azure resources needed to host the Covia venue server:
#   Resource Group → VNet → NSG → Public IP → NIC → VM → Docker → nginx/TLS
#
# Prerequisites:
#   - Azure CLI installed and logged in (az login)
#   - A DNS A record for DOMAIN pointing to the VM's public IP (done after step 5)
#
# Usage:
#   chmod +x setup.sh
#   ./setup.sh
###############################################################################

# ── Configuration (edit these) ───────────────────────────────────────────────
RESOURCE_GROUP="covia-venue-rg"
LOCATION="koreacentral"
VM_NAME="covia-venue-vm"
VM_SIZE="Standard_B2s_v2"         # 2 vCPU, 4 GB RAM (x86)
VM_IMAGE="Canonical:ubuntu-24_04-lts:server:latest"
VM_ADMIN="azureuser"
OS_DISK_SIZE=30                   # GB (Ubuntu 24.04 minimum)
DOMAIN="venue-4.covia.ai"

VNET_NAME="covia-vnet"
SUBNET_NAME="covia-subnet"
NSG_NAME="covia-venue-nsg"
PUBLIC_IP_NAME="covia-venue-ip"
NIC_NAME="covia-venue-nic"
# ─────────────────────────────────────────────────────────────────────────────

echo "=== 1/8  Resource Group ==="
az group create \
  --name "$RESOURCE_GROUP" \
  --location "$LOCATION" \
  --output table

echo "=== 2/8  Virtual Network + Subnet ==="
az network vnet create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VNET_NAME" \
  --address-prefix 10.0.0.0/16 \
  --subnet-name "$SUBNET_NAME" \
  --subnet-prefix 10.0.1.0/24 \
  --output table

echo "=== 3/8  Network Security Group ==="
az network nsg create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$NSG_NAME" \
  --output table

echo "  → Allow SSH (22)"
az network nsg rule create \
  --resource-group "$RESOURCE_GROUP" \
  --nsg-name "$NSG_NAME" \
  --name AllowSSH \
  --priority 1000 \
  --direction Inbound \
  --access Allow \
  --protocol Tcp \
  --destination-port-ranges 22 \
  --output table

echo "  → Allow HTTP (80)"
az network nsg rule create \
  --resource-group "$RESOURCE_GROUP" \
  --nsg-name "$NSG_NAME" \
  --name AllowHTTP \
  --priority 1001 \
  --direction Inbound \
  --access Allow \
  --protocol Tcp \
  --destination-port-ranges 80 \
  --output table

echo "  → Allow HTTPS (443)"
az network nsg rule create \
  --resource-group "$RESOURCE_GROUP" \
  --nsg-name "$NSG_NAME" \
  --name AllowHTTPS \
  --priority 1002 \
  --direction Inbound \
  --access Allow \
  --protocol Tcp \
  --destination-port-ranges 443 \
  --output table

echo "=== 4/8  Static Public IP ==="
az network public-ip create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$PUBLIC_IP_NAME" \
  --sku Standard \
  --allocation-method Static \
  --output table

PUBLIC_IP=$(az network public-ip show \
  --resource-group "$RESOURCE_GROUP" \
  --name "$PUBLIC_IP_NAME" \
  --query ipAddress -o tsv)
echo "  → Public IP: $PUBLIC_IP"

echo "=== 5/8  NIC ==="
az network nic create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$NIC_NAME" \
  --vnet-name "$VNET_NAME" \
  --subnet "$SUBNET_NAME" \
  --public-ip-address "$PUBLIC_IP_NAME" \
  --network-security-group "$NSG_NAME" \
  --output table

echo "=== 6/8  VM ==="
az vm create \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VM_NAME" \
  --nics "$NIC_NAME" \
  --image "$VM_IMAGE" \
  --size "$VM_SIZE" \
  --admin-username "$VM_ADMIN" \
  --generate-ssh-keys \
  --os-disk-size-gb "$OS_DISK_SIZE" \
  --storage-sku StandardSSD_LRS \
  --output table

echo "=== 7/8  Install Docker on VM ==="
az vm run-command invoke \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VM_NAME" \
  --command-id RunShellScript \
  --scripts '
    set -e
    # Install Docker
    apt-get update -y
    apt-get install -y ca-certificates curl
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
      https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
      > /etc/apt/sources.list.d/docker.list
    apt-get update -y
    apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
    systemctl enable --now docker
    usermod -aG docker '"$VM_ADMIN"'

    # Create data directory
    mkdir -p /home/'"$VM_ADMIN"'/covia-data
    chown '"$VM_ADMIN"':'"$VM_ADMIN"' /home/'"$VM_ADMIN"'/covia-data
  '

echo "=== 8/8  Install nginx + Certbot ==="
echo ""
echo "  ┌─────────────────────────────────────────────────────────────┐"
echo "  │  STOP: Before running this step, create a DNS A record:    │"
echo "  │        $DOMAIN  →  $PUBLIC_IP                              │"
echo "  │  Then wait for propagation (check with: dig $DOMAIN)       │"
echo "  └─────────────────────────────────────────────────────────────┘"
echo ""
read -p "  Press Enter once DNS is configured (or Ctrl+C to skip)..."

az vm run-command invoke \
  --resource-group "$RESOURCE_GROUP" \
  --name "$VM_NAME" \
  --command-id RunShellScript \
  --scripts '
    set -e
    apt-get install -y nginx certbot python3-certbot-nginx

    # nginx reverse proxy config
    cat > /etc/nginx/sites-available/covia <<NGINX
server {
    listen 80;
    server_name '"$DOMAIN"';

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
}
NGINX

    ln -sf /etc/nginx/sites-available/covia /etc/nginx/sites-enabled/covia
    rm -f /etc/nginx/sites-enabled/default
    nginx -t && systemctl restart nginx

    # Obtain TLS certificate (non-interactive)
    certbot --nginx -d '"$DOMAIN"' --non-interactive --agree-tos \
      --email ops@covia.ai --redirect
  '

echo ""
echo "==========================================="
echo "  Azure VM provisioning complete!"
echo "==========================================="
echo ""
echo "  Public IP:  $PUBLIC_IP"
echo "  Domain:     $DOMAIN"
echo "  SSH:        ssh $VM_ADMIN@$PUBLIC_IP"
echo ""
echo "  Next steps:"
echo "    1. SSH in and verify: docker ps"
echo "    2. Copy config.json to /home/$VM_ADMIN/covia-data/config.json"
echo "    3. Add GitHub secrets (see README.md)"
echo "    4. Push to develop to trigger deployment"
echo ""
echo "  To get the SSH private key (if auto-generated):"
echo "    cat ~/.ssh/id_rsa"
echo ""

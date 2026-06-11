# Deployment Guide

This guide is for Venue operators who want to deploy a minimal working venue for testing and development purposes

## Server Setup

Have a VM instance with a modern Linux Distro, e.g. Ubuntu 25

```
sudo apt update
```

## Install Java

The venue requires Java 21 or later; any recent JRE works, e.g.:

```
sudo apt-get install -y openjdk-25-jdk
```

## Install Caddy

Install Caddy from its official apt repository:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update
sudo apt install -y caddy
```

Configure `/etc/caddy/Caddyfile` for your domain — the `Caddyfile` in this directory is a working starting point. Then start Caddy:

```bash
sudo systemctl start caddy
```

Can also do:

```bash
sudo caddy start --config /etc/caddy/Caddyfile
```

## Get the Venue JAR

Download `covia.jar` from the GitHub releases on the server:

```bash
# Latest stable release
curl -fLo covia.jar https://github.com/covia-ai/covia/releases/download/latest/covia.jar

# Or the latest develop snapshot
curl -fLo covia.jar https://github.com/covia-ai/covia/releases/download/latest-snapshot/covia.jar
```

Alternatively, copy a locally-built JAR (`venue/target/covia.jar`) to the server via `scp` or your own object storage bucket.


## Run Covia Venue Jar

To run normally at the CLI:

```
java -jar covia.jar ~/.covia/config.json
```

You can omit the config file to get default behaviour

To run in a separate screen session (recommended for test/dev where you want to do other stuff on the server):

```
screen -S covia-venue
java -jar covia.jar ~/.covia/config.json
```

You can switch then:

- back to the main terminal with `Ctrl+A,Ctrl+D`
- list screens with `screen -ls`
- Go back to Covia venue screen with `screen -x co`
- Terminate the Venue with `Ctrl+C`
- kill current screen with `Ctrl+A,k,y`

### Checks

Check which ports you have listening. Should be 80, 443 for Caddy and 8080 for the Venue server

```
netstat -lntup
```

Check the server page

```
curl https://venue-test.covia.ai
```
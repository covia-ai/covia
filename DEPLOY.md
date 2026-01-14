# Deployment Guide

This guide is for Venue operators who want to deploy a minimal working venue for testing and development purposes

## Server Setup

Have a VM instance with a modern Linux Distro, e.g. Ubuntu 25

```
sudo apt update
```

## Install Java

```
sudo apt-get install -y openjdk-25-jdk
```

## Install Caddy

```
sudo cp 
```

Start caddy:

```bash
sudo systemctl start caddy
```

Remember to configure the Caddyfile as necessary at `/etc/caddy/CaddyFile`

## Cloud Jar 

To upload the covia.jar to bucket using GCloud CLI:

```
gsutil cp C:\Users\mike_\git\covia\venue\target\covia.jar gs://covia-jar-bucket/covia.jar
```

To download the covia.jar on server:

```
gsutil cp gs://covia-jar-bucket/covia.jar covia.jar
```


## Run Covia Venue Jar

You will need to obtain the runnable `.jar` file for the Covia Venue.

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
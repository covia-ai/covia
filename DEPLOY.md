# Deployment Guide

This guide is for Venue operators who want to deploy a minimal working venue for testing and development purposes

## Server Setup

Have a VM instance with a modern Linux Distro, e.g. Ubuntu 25

## Install Java

```
sudo apt-get install -y openjdk-25-jdk
```

## Install Caddy

```
sudo apt install -y caddy
```

## Run Covia Venue Jar

You will need to obtain the runnable `.jar` file for the Covia Venue.

To run normally at the CLI:

```
java -jar covia.jar
```

To run in a separate screen session (recommended for test/dev where you want to do other stuff on the server):

```
screen -S covia-venue
java -jar covia.jar
```

You can switch then:

- back to the main terminal with `Ctrl+A,Ctrl+D`
- list screens with `screen -ls`
- Go back to Covia venue screen with `screen -x co`
- Terminate the Venue with `Ctrl+C`


### Checks

Check which ports you have listening. Should be 80, 443 for Caddy and 8080 for the Venue server

```
netstat -lntup
```

Check the server page

```
curl https://venue-test.covia.ai
```
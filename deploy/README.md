# Deployment Guide

This guide is for Venue operators who want to deploy a minimal working venue for testing and development purposes

> Embedding a venue inside a desktop or single-user app (loopback, self-authenticated, one owner)? See the [Embedded Venue](https://docs.covia.ai/docs/operator-guide/embedded-venue) operator guide for that deployment shape.

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
# A specific version (recommended for production — upgrade deliberately)
curl -fLo covia.jar https://github.com/covia-ai/covia/releases/download/0.1.0/covia.jar

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

Check the server page (using your own venue's domain)

```
curl https://venue.example.com/api/v1/status
```

## Admit users at runtime

The built-in `UserAdapter` is the canonical runtime provisioning surface. Its
`v/ops/user/create` operation accepts either a complete external DID or a
venue-managed `username`; see [venue configuration](../venue/docs/CONFIG.md#user-registration-users)
for the identity and authentication-key details.

Operator code running inside the venue process can submit the operation as the
venue itself. Use the ordinary job path so that the administrative action has
a durable job record:

```java
ACell admitted = engine.jobs().invokeOperation(
    "v/ops/user/create",
    Maps.of(Fields.DID, Strings.create("did:key:z6Mk...")),
    engine.venueContext()).awaitResult(5_000);
```

An operator-installed adapter can use the same call. Code given
`engine.venueContext()` acts with venue authority, so only trusted operator
modules should receive it.

A remote provisioning service does not need a separate administration API.
Give it a venue-issued UCAN for `<venueDID>/users` with the `user/create`
ability, then invoke `v/ops/user/create` through the normal REST, MCP, or client
SDK surface. `UserAdapter` checks that delegation at the operation boundary.

For fixed initial accounts, use `users.bootstrap` instead. For a public test
venue where any successfully authenticated DID should be admitted on first
use, set `users.autoCreate` to `true`. Admission policy is venue-specific;
clients should not assume that another venue uses the same policy.

## Concurrent SSE Viewers (sizing)

Job streaming (`GET /api/v1/jobs/{id}/sse`) holds one connection per viewer on
a virtual thread — connections are cheap and do not consume platform threads.
Measured on a developer-class machine (single venue JVM, default heap):

- **500 concurrent viewers** on one job: all connected in ~2s, the initial
  `job-update` frame delivered to all 500, and a single broadcast (job status
  change) reached all 500 in ~2s, zero errors. Platform thread count stayed
  ~85 regardless of connection count; total JVM working set ~400MB.
- Broadcast fan-out is sequential per subscriber (~4ms/client per update) —
  fine for demo-scale audiences on one hot job; thousands of subscribers on a
  rapidly-updating job would stretch update latency linearly.

**Rate limiter interaction.** SSE connects pass through the per-caller token
bucket like any `/api/*` request. All anonymous viewers share the `:public`
bucket, so a thundering herd sheds the overflow with `429 + Retry-After`
(measured: 500 simultaneous anonymous connects on default limits `rps=100,
burst=300` → 316 immediate, 184 shed, and every shed client connected on its
first retry — the whole herd inside 6s). Browser `EventSource` auto-retries,
so shedding is invisible to real viewers. For larger audiences either raise
`rateLimit.burst` on the demo venue, or have viewers authenticate as
self-issued session identities (each gets its own bucket).

**Degradation strategy.** For public demos, pair live streaming with a
recorded-run fallback: a run's complete record (session frames + timeline) is
ordinary lattice data — pin it as a content-addressed asset and replay it
client-side when the venue is unreachable.

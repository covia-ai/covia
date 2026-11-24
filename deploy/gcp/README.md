# GCP Deployment (Stable Venues)

Docker-based deployment for the stable venues `venue-test.covia.ai`,
`venue-1.covia.ai` and `venue-2.covia.ai`, in GCP project
`idyllic-pottery-468305-u2`, zone `asia-southeast1-b`.

Since 2026-11-24 the venues run on two hosts, each venue in its own container
with only its own directory mounted, so venue-test is isolated from the stable
pair (security#6):

| Host | Machine | Address | Venues |
|------|---------|---------|--------|
| `covia-stable` | e2-medium (2 vCPU / 4 GB) | `35.213.147.8` (static `venue-covia-ai`, Standard tier) | `venue-1` (8081), `venue-2` (8082) |
| `covia-venue-test` | e2-small (2 vCPU / 2 GB) | `34.126.87.243` (static `venue-test-covia-ai`) | `venue-test` (8080) |

Both run Ubuntu 24.04 LTS with OS Login, Shielded VM (Secure Boot) and the
`daily-35d` snapshot schedule on the boot disk. SSH is reachable from Google's
IAP range only (`ssh-iap-only` network tag).

`venue-test.covia.ai` is intentionally open for authenticated first-use
registration (`users.autoCreate: true`). Any caller that proves control of a
valid DID is admitted on its first operation. `venue-1` and `venue-2` retain
the default closed admission policy and require explicit provisioning.

The containers run the image digest recorded by `publish-docker.yml` after
successful master validation (also tagged `:stable`), and are redeployed
automatically by `deploy-gcp.yml`. The QA venues (`venue-3`, `venue-4`) track
`qa` via the `:latest` image; these hosts only change at releases.

## Host Layout

On each host:

- **Containers:** `covia-<venue>` (`covia-venue-1`, `covia-venue-2`,
  `covia-venue-test`), one JVM per venue: `-Xmx1g` for venue-1 and venue-2,
  `-Xmx768m` for venue-test. Ports are published on `127.0.0.1` only, so the
  plain-HTTP ports are reachable just through Caddy. `--restart unless-stopped`
  with a Docker health check; json-file log rotated at 5 x 50 MB.
- **Venue directory:** `/srv/covia/<venue>/` (owned by uid 1001, mode 0750),
  mounted at `/data` in that venue's container only:
  - `config.json`: a `venues` array with one venue. The deploy writes it on
    first install only, then reconciles `trustedProxies`, audit and access
    logging, and (venue-test) `users.autoCreate`, keeping other operator
    edits.
  - `data/venue.etch` and `data/venue.key`: the store and the venue's
    identity, which is always read from `venue.key` next to the store. A store
    without its key fails at startup rather than starting a new identity.
- **Caddy:** systemd `caddy.service` from the official Caddy repository,
  config at `/etc/caddy/Caddyfile`, one site per venue on that host with HSTS:

```
{
        email admin@covia.ai
}

venue-1.covia.ai {
        header Strict-Transport-Security "max-age=31536000"
        reverse_proxy 127.0.0.1:8081
}

venue-2.covia.ai {
        header Strict-Transport-Security "max-age=31536000"
        reverse_proxy 127.0.0.1:8082
}
```

  `covia-venue-test` has the same shape with `venue-test.covia.ai` →
  `127.0.0.1:8080`.
- **Packages:** Docker CE from Docker's repository. Unattended upgrades cover
  Ubuntu security, Docker and Caddy, with no automatic reboot.
- **Monitoring:** the Ops Agent sends memory and disk metrics. Alert policies
  "GCP venue hosts unavailable / high CPU / high memory / disk filling" cover
  both hosts; uptime checks are by hostname.

## Deploy identity (no SSH key)

There is no stored SSH key. `deploy-gcp.yml` signs in to GCP over GitHub OIDC
(Workload Identity Federation) and runs [`deploy.sh`](deploy.sh) on each host
as root through an IAP tunnel with OS Login, one host at a time:

```bash
gcloud compute ssh covia-stable --zone asia-southeast1-b --tunnel-through-iap \
  --command "echo $(base64 -w0 deploy/gcp/deploy.sh) | base64 -d | sudo DEPLOY_IMAGE='<image@digest>' bash -s -- venue-1 venue-2"
```

`deploy.sh` redeploys the venues it is given one at a time, so the others on
the host stay up. It fails, leaving the rest untouched, if a venue does not
come back with the same DID it had before the restart.

| Piece | Value |
|-------|-------|
| Workload Identity provider | `projects/323115557788/locations/global/workloadIdentityPools/github/providers/covia-covia` |
| Provider condition | `assertion.repository_id == '813519888' && assertion.workflow_ref == 'covia-ai/covia/.github/workflows/deploy-gcp.yml@refs/heads/develop'` |
| Deploy service account | `covia-deploy-gcp@idyllic-pottery-468305-u2.iam.gserviceaccount.com` |
| Its grants (the two hosts only) | `roles/compute.osAdminLogin`, `roles/iap.tunnelResourceAccessor`, custom `coviaInstanceGetter` (`compute.instances.get`); plus `roles/iam.serviceAccountUser` on the hosts' compute service account, which OS Login requires |
| Its project grant | custom `coviaProjectGetter` (`compute.projects.get` only): `gcloud compute ssh` reads project metadata to check OS Login |

The condition names `develop` because `workflow_run` always runs the workflow
from the default branch; the image deployed is still the one published from
`master`. None of these values is secret, so they are written in the workflow.

OS Login is on and project `ssh-keys` metadata is blocked on both hosts.
Admins log in with an account that has `roles/compute.osAdminLogin` and
`roles/iap.tunnelResourceAccessor` on the instance:

```bash
gcloud compute ssh covia-stable --zone asia-southeast1-b --tunnel-through-iap
```

An account outside the project's Google Cloud organization also needs
`roles/compute.osLoginExternalUser` granted at organization level; until it
has that, it can sign in by impersonating a service account in the project
that holds the roles above (`--impersonate-service-account`).

## Manual Deploy

Run *Deploy to GCP* with a master publishing run ID, following
[the redeploy procedure](../../BUILD.md#deployment-identity-and-redeploys).

For an ad hoc deploy on a host, run `deploy.sh` there with the image digest
and that host's venues:

```bash
sudo DEPLOY_IMAGE=ghcr.io/covia-ai/covia@sha256:<digest> bash deploy/gcp/deploy.sh venue-1 venue-2   # covia-stable
sudo DEPLOY_IMAGE=ghcr.io/covia-ai/covia@sha256:<digest> bash deploy/gcp/deploy.sh venue-test        # covia-venue-test
```

## Rollback

Every published build is also tagged with its short commit SHA. To roll
back, deploy that build's digest (`docker buildx imagetools inspect
ghcr.io/covia-ai/covia:<sha>`) with `deploy.sh` as above.

## Notes

- The image is published by the image-publication workflow and deployment
  consumes that registry artifact; hosts do not build a bootstrap image
  on-box.
- Heaps: venue-1 and venue-2 share 4 GB at `-Xmx1g` each; venue-test has
  2 GB at `-Xmx768m`. All three together used about 660 MB of heap before
  the split. Raise a heap only if its machine type grows.
- Until 2026-11-24 all three venues ran in one JVM (one `covia-venue`
  container on a single Ubuntu 25.04 host). That host is kept stopped for
  rollback until it is retired.

# Venues on Kubernetes

[`venue.yaml`](venue.yaml) runs one venue on a Kubernetes cluster, from the
public image `ghcr.io/covia-ai/covia`. It is plain YAML: apply it with
`kubectl`, or with any tool that takes manifests.

| Object | Name | What it is |
|--------|------|------------|
| ConfigMap | `covia-venue-config` | The venue config, `config.json5` |
| StatefulSet | `covia-venue` | One pod running the venue, with a volume of its own at `/data` |
| Service | `covia-venue` | The pod's address inside the cluster, port 80 |
| Ingress | `covia-venue` | The public hostname, with TLS ended at the ingress |

## What the cluster needs

- An ingress controller. The Ingress names no class, so the cluster's default
  IngressClass serves it, or a controller that takes Ingresses without one,
  as Contour does; set `spec.ingressClassName` to choose another.
- A default StorageClass, which the volume is claimed from.
- A DNS name for the venue pointing at the ingress controller.
- A certificate for that name: the Ingress asks
  [cert-manager](https://cert-manager.io) for one from the issuer it names.
  Without cert-manager, remove the annotation and create the TLS Secret
  `covia-venue-tls` yourself.

## Deploy a venue

Change the values marked `CHANGE` in `venue.yaml`:

- `hostname` in the config, and the same name twice in the Ingress. The
  venue's DID is `did:web:<hostname>`.
- `name`, the venue's display name.
- `image`: pin a release, `ghcr.io/covia-ai/covia:<x.y.z>`. `stable` moves
  with each release, and a node keeps the copy it pulled first.
- `trustedProxies`: the address range the ingress controller's pods connect
  from, usually the cluster's pod range. The venue takes a caller's address
  from `X-Forwarded-For` only on connections from there
  ([CONFIG.md, Trusted proxies](../../venue/docs/CONFIG.md#trusted-proxies-trustedproxies)).
- The certificate issuer annotation on the Ingress.

The config follows the production profile
([CONFIG.md](../../venue/docs/CONFIG.md#production-profile)): no anonymous
access, no self-registration, and unknown settings refused. Admit users as
[the operator guide](../README.md#admit-users-at-runtime) describes.

Give each venue a namespace of its own, so several venues can use the same
file unchanged apart from their values:

```bash
kubectl create namespace venue-example
kubectl apply -n venue-example -f venue.yaml
kubectl -n venue-example rollout status statefulset/covia-venue
curl https://venue.example.com/api/v1/status
```

## The pod and its volume

- There is never more than one pod: the store has a single writer. A
  StatefulSet stops the old pod before starting its replacement; a Deployment
  could run both at once on the same volume.
- The volume holds the store, `/data/venue.etch`, and, unless the identity is
  kept in a Secret (below), the venue's identity key, `/data/venue.key`, which
  the venue creates on its first start. Back up the volume as a whole, while
  the venue is stopped: scale the StatefulSet to 0 replicas, copy or snapshot
  the volume, and scale it back to 1.
- Deleting the StatefulSet keeps the volume. Deleting its claim,
  `data-covia-venue-0`, or the venue's namespace removes the volume too, with
  the store and identity key on it, wherever the StorageClass's reclaim policy
  is `Delete`, the Kubernetes default.
- The image runs as user 1001; `fsGroup` makes the volume writable for it.
- The heap is 75% of the memory limit, set by the image's `JAVA_OPTS`.
- The probes call `/api/v1/status`, which answers anyone, so they work with
  anonymous access off. The startup probe allows five minutes, for a store
  collected at start-up (`etch.gc.onStart`).

## Where the venue's identity lives

The venue's identity is an Ed25519 seed. It signs as the venue, its DID is
made from it, and the store's state belongs to it: the venue refuses to start
on a store with any other key
([CONFIG.md, Venue identity](../../venue/docs/CONFIG.md#venue-identity)).
Whichever way it is kept, back it up with the volume.

- **On the volume**, as `venue.yaml` does unless changed: the venue creates
  `/data/venue.key` on its first start. Nothing else to set up; the DID is
  known once the venue has started.
- **In a Secret**, for operators who keep keys in their secret tooling, want
  the DID before the first start, or encrypt the store and want the identity
  off its disk. Create the Secret with 32 random bytes as hex:

  ```bash
  kubectl -n venue-example create secret generic covia-venue-identity \
    --from-literal=seed="$(openssl rand -hex 32)"
  ```

  Then uncomment the `identity` volume and its mount in `venue.yaml`, and add
  `"seed": { "file": "/secrets/seed" }` to the venue's config. The seed can
  equally come from an environment variable set from the Secret,
  `"seed": { "env": "COVIA_SEED" }`. To move an existing venue's identity into
  a Secret, put the hex from its `/data/venue.key` in the Secret instead of a
  new seed.

## Pausing, restarting and crashes

- **Pausing.** Kubernetes cannot freeze a running container. To pause a
  venue, scale it to 0 (`kubectl -n venue-example scale
  statefulset/covia-venue --replicas=0`): the pod stops and the volume stays.
  Scale it back to 1 to resume.
- **Stopping.** Kubernetes stops a pod by sending it SIGTERM, on a scale to 0,
  a deletion, an image or config change, or a node being drained, and kills it
  if it has not stopped within `terminationGracePeriodSeconds` (30). On
  SIGTERM the venue takes no new work, gives running jobs up to
  `shutdown.graceMs` (2 seconds) to finish, then pauses those that can be
  paused and cancels the rest with "Venue shut down", and saves the store
  ([JOBS.md, Shutdown](../../venue/docs/JOBS.md#shutdown)). Paused and
  finished jobs are kept. Keep `terminationGracePeriodSeconds` well above
  `shutdown.graceMs`.
- **Crashes.** Running out of memory or losing the node kills the venue with
  no warning. On its next start the store opens as it was, losing at most the
  writes of the last 100 milliseconds after a process crash or 10 seconds
  after a machine crash ([PERSISTENCE.md](../../venue/docs/PERSISTENCE.md));
  jobs that were running are failed with "effects may or may not have
  applied; verify state before retrying", and paused jobs come back
  ([JOBS.md, Recovery on restart](../../venue/docs/JOBS.md#recovery-on-restart-214)).
  An encrypted store (`etch.version: 3`) that crashes with unsaved writes
  refuses to open until it is repaired, so the pod restarts repeatedly until
  someone repairs or restores it.
- **Never two at once.** The StatefulSet starts a replacement only after the
  old pod has gone, the venue locks its store file, and a `ReadWriteOnce`
  volume attaches to one node at a time. Never force-delete a venue's pod on a
  node that has stopped responding: Kubernetes may then start a second copy
  while the first still runs.

## What the ingress controller must allow

Two settings of the ingress controller decide whether a venue works through
it, and each controller has its own way to set them. `venue.yaml` carries
Contour's; for another controller, find its equivalents.

- **HTTPS only.** A controller may serve plain HTTP as well as HTTPS for an
  Ingress with TLS, and callers then send bearer tokens unencrypted. Contour
  does so unless the Ingress carries
  `ingress.kubernetes.io/force-ssl-redirect: "true"`, which redirects plain
  HTTP to HTTPS.
- **Long responses.** An `invoke` that waits for its result sends nothing
  until the result is ready, and job and agent event streams (`/sse`) and MCP
  sessions stay open, often quiet, for as long as the work lasts. Contour ends
  every response after 15 seconds unless the Ingress carries
  `projectcontour.io/response-timeout: infinity`.

## Changing and upgrading a venue

- A config change takes effect when the pod restarts:
  `kubectl -n venue-example rollout restart statefulset/covia-venue`.
- To upgrade, back up the volume, change `image` and apply the file again;
  the StatefulSet replaces the pod. Read the [operator guide](../README.md)
  for upgrade notes first.
- A newer version may upgrade the store, and migration only goes forward: an
  older version refuses a store a newer one has upgraded, and writes nothing
  to it ([MIGRATE.md](../../venue/docs/MIGRATE.md)). To roll back, restore
  the backup taken before the upgrade, with the venue stopped, and apply the
  previous image.
- The venue's own `venue:restart` operation restarts the jar inside the
  container, not the image; on Kubernetes, change the image instead.

## Logs

`kubectl -n venue-example logs statefulset/covia-venue` shows one JSON object
per line (`operations.log-format`). Audit and access lines, off by default,
are turned on per venue with `logging`
([CONFIG.md, Logging](../../venue/docs/CONFIG.md#logging)) and carry the
logger names `AUDIT` and `ACCESS` for a log shipper to route on.

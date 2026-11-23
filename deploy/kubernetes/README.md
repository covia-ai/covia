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

- An ingress controller. The Ingress uses the cluster's default IngressClass;
  set `spec.ingressClassName` to use another.
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
- The volume holds the store, `/data/venue.etch`, and the venue's identity
  key, `/data/venue.key`, which the venue creates on its first start. The
  store's state belongs to that key, and the venue refuses to start on it with
  another ([CONFIG.md, Venue identity](../../venue/docs/CONFIG.md#venue-identity)).
  Back up the volume as a whole, while the venue is stopped: scale the
  StatefulSet to 0 replicas, copy or snapshot the volume, and scale it back
  to 1.
- The image runs as user 1001; `fsGroup` makes the volume writable for it.
- The heap is 75% of the memory limit, set by the image's `JAVA_OPTS`.
- The probes call `/api/v1/status`, which answers anyone, so they work with
  anonymous access off. The startup probe allows five minutes, for a store
  collected at start-up (`etch.gc.onStart`).

## Long-lived responses

Job and agent event streams (`/sse`) and MCP sessions can go quiet for long
spells. Ingress controllers close a response that sends nothing for a while,
ingress-nginx after 60 seconds by default. Raise the read timeout on the
Ingress, for ingress-nginx with:

```yaml
nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
```

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

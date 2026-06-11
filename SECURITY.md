# Security Policy

Covia is infrastructure for **federated AI orchestration** — venues execute operations, hold per-user secrets, issue capability tokens, and talk to one another across organisational boundaries. We take security reports seriously and appreciate the time researchers and operators spend to make the project safer.

## Reporting a vulnerability

**Please do not open a public issue, discussion, or pull request for a security vulnerability.**

Report privately through one of:

- **GitHub Private Vulnerability Reporting** (preferred) — on this repository, go to the **Security** tab → **Report a vulnerability**. This keeps the report and our coordination private until a fix is ready.
- **Email** — [security@covia.ai](mailto:security@covia.ai). Encrypt with our PGP key if you can; if you need the key, ask in the first (unencrypted) message and we'll respond.

Please include, as far as you can:

- a description of the issue and its impact;
- the affected component, version, or commit (`git rev-parse HEAD`, or the release tag);
- steps to reproduce, a proof of concept, or a failing request;
- any suggested remediation.

## What to expect

- **Acknowledgement** within **3 business days**.
- An initial **assessment and severity** within **10 business days**.
- Regular updates as we work on a fix, and credit in the release notes once it ships (unless you prefer to remain anonymous).
- **Coordinated disclosure:** we'll agree a disclosure timeline with you and publish an advisory when a fix is available. Please give us reasonable time to remediate before going public.

We will not pursue legal action against researchers who act in good faith, avoid privacy violations and service disruption, and follow this policy.

## Supported versions

Covia is pre-1.0 and moving fast. Security fixes land on `develop` and ship in the next snapshot/release; we do not currently backport to older tags.

| Version | Supported |
|---------|-----------|
| `develop` / latest snapshot | ✅ |
| Tagged releases `< 1.0` | ⚠️ Best effort — upgrade to the latest |

If you run Covia in production, track the latest release and the [DX_PLAN.md](DX_PLAN.md) roadmap.

## Scope

**In scope** — vulnerabilities in this repository, including:

- the venue server and HTTP surface (REST, MCP, A2A, SSE, DID endpoints);
- authentication and authorisation (`NoAuth`, `BearerAuth`, `KeyPairAuth`, `LocalAuth`, job ownership, UCAN capability issuance and verification);
- secret storage, redaction, and reference resolution;
- SSRF protection in the HTTP adapter and other outbound-request paths;
- the adapter framework and the bundled adapters;
- content addressing, lattice persistence, and federation/cross-venue calls.

**Out of scope** — typically not something we can fix here:

- vulnerabilities in third-party LLM providers, MCP servers, or remote venues you choose to call;
- issues caused by operator misconfiguration (e.g. running with `NoAuth` on a public network, disabling SSRF protection, exposing a venue without TLS) — though if our defaults make this *easy to get wrong*, we want to hear about it;
- vulnerabilities in dependencies that already have a public advisory and a fix available (open a normal issue or PR to bump them);
- denial of service from unbounded but documented behaviour (e.g. long-running jobs have no framework timeout by design) — see the rate-limiting work tracked in `AGENTS.md`.

Vulnerabilities in the upstream [Convex](https://github.com/Convex-Dev/convex) lattice platform should be reported to that project; if you're unsure where a problem lives, report it to us and we'll route it.

## A note on the trust model

A venue trusts the operators it federates with. Covia provides UCAN-based capabilities, per-user secret isolation, job-ownership enforcement, and SSRF protection as building blocks, but **the security of a federation depends on the policies its operators configure**. Capability enforcement is still being hardened (see `AGENTS.md`); treat the current cross-venue trust surface as evolving, and review the [operator guide](https://docs.covia.ai/docs/operator-guide/) before exposing a venue.

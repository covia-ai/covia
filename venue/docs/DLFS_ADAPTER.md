# DLFS, File, and Vault Storage Surfaces

This document describes the current storage architecture. The Java NIO DLFS
provider is the persistence layer; WebDAV is the byte transport; Covia adapters
are the governed operation surfaces used by agents.

## Layers

1. **DLFS provider** — mutable, path-addressed CRDT filesystem state in the
   venue lattice. Each user's drives are signed with that user's DLFS key.
2. **WebDAV** — authenticated streaming transport mounted at `/dlfs/` when
   enabled. It is appropriate for large byte uploads/downloads, not structured
   agent operations.
3. **Adapters** — `file`, `dlfs`, and `vault`, all using the same
   backend-neutral `FileOperations` implementation for shared file behavior.

Authorization is intentionally outside `FileOperations`. Each adapter resolves
and authorises a target before invoking the shared implementation.

## Common file contract

Operations with the same name have the same inputs and response semantics:

| Operation | Purpose |
|-----------|---------|
| `list` | List direct children with `name`, `type`, `size`, and `modified` |
| `read` | Read using `auto`, `text`, `bytes`, or `json` mode |
| `write` | Create/overwrite from exactly one of `content`, `value`, `bytes`, or `asset` |
| `mkdir` | Create a directory; `parents=true` creates missing parents |
| `delete` | Delete a target; `recursive=true` removes a populated directory |

Adapters may expose useful extensions. `file` and `dlfs` currently also expose
`tree`, `append`, and `stat`; `file` additionally exposes `move`, `copy`, and
configured-root discovery. DLFS additionally exposes drive lifecycle operations.

`read` returns:

- `text`: UTF-8 `content`, or an error if the bytes are not valid text.
- `bytes`: base64 `content` with `encoding: "base64"`.
- `json`: parsed `value`.
- `auto`: UTF-8 `content` for text. For binary DLFS content it returns an
  authenticated WebDAV `url` when that URL addresses the caller's own drive;
  otherwise it returns inline base64.

## DLFS addressing and authority

DLFS uses the canonical DID-scoped resource namespace:

```text
dlfs/<drive>/<path>                    # caller's own drive
<ownerDID>/dlfs/<drive>/<path>         # another user's drive
```

The own-drive shorthand is canonicalised to the caller DID by capability
checking. Cross-user access requires presented proofs covering the owner-scoped
resource and requested ability. Opening an owner's drive and signing mutations
under its custodial key occurs only after that check.

Paths are normalised and jailed at the drive root before filesystem access.
Drive names are one non-empty segment and cannot contain `/`, `\`, or `:`.

## File adapter

The File adapter supports operator-configured host, temporary, and DLFS-backed
roots. Host/temp targets use `file://<root>/<path>` capabilities.

DLFS-backed configured roots are aliases and optional subtree jails, not a
second authority namespace. Their capabilities always name the underlying
canonical DLFS resource. A `file://alias/...` grant cannot authorize DLFS data.

File operations also accept canonical DLFS paths directly without a configured
root. Omit `root` and pass the canonical path in `path`; `move` and `copy`
accept the same forms in `from` and `to`.

## Vault adapter

Vault is a convenience binding over DLFS. It removes the `drive` input and
injects the operator-configured `adapters.vault.drive`, which defaults to
`vault`. Runtime behavior delegates through DLFS's typed operation boundary;
there is no separate vault filesystem implementation or permission model.

The configured vault drive is created on first use. Enabling Vault without a
declared encrypted Etch policy emits an operator warning because DLFS content
may otherwise be stored unencrypted at rest.

## Content references

`DLFSAdapter` is also a `ContentProvider`, so canonical DLFS file references can
be used wherever Covia resolves content. Reads are lazy `PathContent`; writes
stream directly. A caller-supplied `asset` reference in a cross-user write is
resolved under the caller's authority, never the drive owner's authority.

DLFS is not a replacement for immutable content-addressed assets and should not
contain format-specific parsing logic. Those concerns belong to asset storage
and dedicated adapters respectively.

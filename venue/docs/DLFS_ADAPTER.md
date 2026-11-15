# DLFS, File, and Vault Storage Surfaces

This document describes the current storage architecture. The Java NIO DLFS
provider is the persistence layer; WebDAV is the byte transport; Covia adapters
are the governed operation surfaces used by agents.

## Layers

1. **DLFS provider** — mutable, path-addressed CRDT filesystem state in the
   venue lattice. Each user's drives are signed with that user's DLFS key.
2. **WebDAV** — streaming transport mounted at `/dlfs/` when enabled. It is
   appropriate for large byte uploads/downloads, not structured agent
   operations. The venue does not authenticate these requests: each acts as
   the venue's public identity, outside the adapters' checks, so whoever
   enables WebDAV controls access in front of `/dlfs/`.
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
| `write` | Create/overwrite from exactly one of `content`, `value`, `bytes`, or `contentRef` |
| `mkdir` | Create a directory; `parents=true` creates missing parents |
| `delete` | Delete a target; `recursive=true` removes a populated directory |
| `setMetadata` | Set or clear a DLFS entry's metadata (see Metadata) |

Adapters may expose useful extensions. `file` and `dlfs` currently also expose
`tree`, `append`, and `stat`; `file` additionally exposes `move`, `copy`, and
configured-root discovery. DLFS additionally exposes drive lifecycle operations.

In a DLFS drive, a file that `create` or `write` makes or replaces changes in
one update, once all its content has arrived. Readers see the old file or the
new one, and content that fails part-way, such as an upload cut off, leaves the
drive as it was. Content uploaded to a DLFS reference and files that `archive`
extracts or zips into a drive are written the same way. `append` adds its
content as it arrives. A file cannot replace a directory: such a write fails as
a conflict.

`read` returns:

- `text`: UTF-8 `content`, or an error if the bytes are not valid text.
- `bytes`: base64 `content` with `encoding: "base64"`.
- `json`: parsed `value`.
- `auto`: UTF-8 `content` for text. For binary DLFS content it returns an
  WebDAV `url` when that URL addresses the caller's own drive;
  otherwise it returns inline base64.

## Metadata

Every DLFS entry, file or directory, can carry one application-defined value,
which Covia stores without interpreting. `setMetadata` sets it, replacing any
it had, or clears it with null. It needs `crud/write` on the entry, which must
exist, and creates nothing: unlike other writes, it does not create a
component's own drive or root. It is offered by `dlfs`, by `file` for a DLFS
target, and by `vault`.
`stat` returns it as `metadata`, absent when there is none. The value belongs
to the entry:

- a move or rename takes it along;
- replacing a file's content in place, through an operation or WebDAV, keeps
  it;
- deleting the entry removes it, so an entry that later takes its path,
  created there or moved there, has only its own.

Setting it is one transaction on the drive that leaves the entry's content and
modification time as they were, so a write to the entry made meanwhile is
never lost. Host and temp files under `file` roots have no metadata, and
`file:setMetadata` refuses them.

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

A drive the caller names must exist: only `createDrive` creates one (over
WebDAV, `MKCOL /dlfs/<drive>/`). Every other operation, writes included, fails
with no such drive on a drive that is absent or deleted, and leaves the owner's
drives and keys as they were; over WebDAV it answers 404. This holds for `dlfs`
operations, content references to `dlfs/<drive>/<path>`, and a caller's
`destination`.

Creating an entry changes the directory that holds it. A write that creates a
file or directory therefore needs `crud/write` on that directory, or on the
nearest one that exists when several levels are missing, as well as on the
entry. A grant over an existing file changes it but creates nothing. Likewise,
creating a drive adds it to its owner's drives, so `createDrive` needs
`crud/write` over `[<owner>/]dlfs`; a grant over the drive alone does not
create it. Operations name the drive in `drive`: a bare name, or
`<ownerDID>/<drive>` for another user's drive under their grant. `createDrive`
and `deleteDrive` also accept `name` as an alias for a bare name.

A component that names its own drive creates it, as the drive's owner, with
its first write there. These drives are Vault's configured drive, a DLFS-backed
`file` root (with its subpath), and the drive a messaging provider or speech
stores media in by default (`<owner>/dlfs/<provider>/`). Like a user's
namespaces, such a root counts as present. The write is authorised as usual:
a new file at the drive's root needs `crud/write` over the drive. Only then is
the root created. Messaging and speech go through `DLFSAdapter.ensureDrive`.
Reads never create a drive.

## File adapter

The File adapter supports operator-configured host, temporary, and DLFS-backed
roots. Host/temp targets use `file://<root>/<path>` capabilities.

A host or temporary root confines every write to what is really inside it. A
path is followed through any links and refused if it leads outside the root,
and a write never follows a link at the file's own path: writing to a link is
refused as bad input. Every route that writes into a root applies both checks,
`archive:extract` included, for each file it writes.

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

Vault creates its configured drive with its first write; until then a read
finds no drive. Enabling Vault without a
declared encrypted Etch policy emits an operator warning because DLFS content
may otherwise be stored unencrypted at rest.

## Content references

`DLFSAdapter` is also a `ContentProvider`, so canonical DLFS file references can
be used wherever Covia resolves content. Reads are lazy `PathContent`; writes
stream directly. This includes `GET /api/v1/content/<ref>`, `asset:content
{ref}`, and every operation with a `contentRef` input. A caller-supplied
`contentRef` in a cross-user write is resolved under the caller's authority,
never the drive owner's authority. The historical `asset` input name remains
an alias for compatibility.

DLFS is not a replacement for immutable content-addressed assets and should not
contain format-specific parsing logic. Those concerns belong to asset storage
and dedicated adapters respectively.

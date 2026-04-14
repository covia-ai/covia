# DLFS Adapter

**Status:** Design (target architecture)
**Scope:** `DLFSAdapter`, `VaultAdapter`, and the `/dlfs/*` HTTP surface

## Layering Principles

Three layers, each with a narrow responsibility:

```
┌────────────────────────────────────────────────────────────┐
│ Layer 3 — Adapter (DLFSAdapter, VaultAdapter)              │
│   • Covia-facing operations: read/write/list/mkdir/delete  │
│   • Utilities for translating files <-> workspace values   │
│   • Encoding / decoding, MIME inference, text heuristics   │
│   • Auth via RequestContext, per-user drives               │
├────────────────────────────────────────────────────────────┤
│ Layer 2 — Transport (WebDAV handler at /dlfs/*)            │
│   • Raw-byte HTTP: GET / PUT / DELETE / MKCOL / PROPFIND   │
│   • Sets Content-Type on reads                             │
│   • Shares auth with the REST API                          │
├────────────────────────────────────────────────────────────┤
│ Layer 1 — DLFS (convex.dlfs)                               │
│   • Pure byte-oriented filesystem over a lattice cursor    │
│   • No encoding, no MIME, no string interpretation         │
│   • Same store used by Layer 2 and Layer 3                 │
└────────────────────────────────────────────────────────────┘
```

**Hard rule:** Layer 1 never sees encoded data. Bytes in, bytes out.
**Soft rule:** Encoding lives in Layer 3. Layer 3 exists precisely so agents
(and other Covia operations) can get typed, structured values out of — and
into — a byte-oriented store without every caller having to do the
conversion itself.

## Layer 1 — DLFS (pure filesystem)

Already correct. DLFS is a `java.nio.file.FileSystem` backed by a lattice
cursor (`:dlfs → <AccountKey> → :value`). Operations on files deal in
`byte[]` and `InputStream` / `OutputStream`. Nothing downstream needs to
change here; the design only needs to hold the line: **never teach DLFS
about encodings or MIME types.**

## Layer 2 — WebDAV transport

The `/dlfs/{drive}/{path}` handler already exists and writes to the same
lattice store that Layer 3 reads. Two gaps to close:

### 2.1 Content-Type on GET

Today the GET handler returns bytes with no `Content-Type`, so browsers
render binaries as octet-stream. Target:

- Infer MIME by extension first (cheap, correct for common cases).
- Fall back to magic-byte sniff of the first ~64 bytes for extensionless
  files or mismatches.
- Default to `application/octet-stream` if neither yields a match.

The MIME utility lives in Layer 3 (see `MimeUtils` below) and is reused by
the transport. This is the one place Layer 2 reaches "up" — it's a shared
concern, not a layering violation.

### 2.2 Authentication

Currently `/dlfs/*` bypasses `AuthMiddleware` and falls back to the venue's
public DID. Target:

- Extend `AuthMiddleware` to cover `/dlfs/*` using the same JWT/bearer
  scheme as `/api/*`.
- Caller DID is resolved once per request; `DLFSDriveManager` uses it to
  select the per-user drive.
- Unauthenticated access is allowed only when public access is enabled
  (matching current ops behaviour).

### 2.3 Scope

WebDAV is the transport for **bytes**. It is not an operations API —
structured responses (listings, metadata) stay on the ops API. The one
exception is PROPFIND, which WebDAV clients expect; that's fine because
its output is a fixed WebDAV format, not a Covia schema.

## Layer 3 — DLFSAdapter (Covia operations)

This is the contract agents see. Target surface:

### 3.1 Operations

| Operation | Accepts | Returns |
|-----------|---------|---------|
| `dlfs:list` | `drive`, `path?` | `{ entries: [{ name, type, size? }] }` |
| `dlfs:read` | `drive`, `path` | See §3.2 |
| `dlfs:write` | `drive`, `path`, plus one of `content` / `asset` / `value` | `{ written, created }` |
| `dlfs:mkdir` | `drive`, `path` | `{ created }` |
| `dlfs:delete` | `drive`, `path` | `{ deleted }` |
| `dlfs:listDrives` | — | `{ drives: [...] }` |
| `dlfs:createDrive` | `name` | `{ created }` |
| `dlfs:deleteDrive` | `name` | `{ deleted }` |

`VaultAdapter` wraps the file operations with a hardcoded `drive:
"health-vault"`. No behavioural differences beyond the drive binding.

### 3.2 Read — always returns a useful value

`dlfs:read` is the adapter's primary translation point. Given a file of
unknown nature, it produces a value the caller can use directly. The
caller declares what it wants via a `mode` parameter; the default is
`auto`, which does the right thing for typical agent use.

Input:

| Field | Type | Purpose |
|-------|------|---------|
| `drive` | string | Required |
| `path` | string | Required |
| `mode` | enum | `auto` (default), `text`, `bytes`, `json`, `asset` |
| `maxSize` | int | Size guard, default 1 MB |

Output (depends on mode; `encoding` is always present and unambiguous):

| Mode | Output shape |
|------|--------------|
| `text` | `{ content: "...", encoding: "utf-8", size, mime }` — error if not valid UTF-8 |
| `bytes` | `{ content: "<base64>", encoding: "base64", size, mime }` |
| `json` | `{ value: <parsed JSON>, size, mime }` — error if not JSON |
| `asset` | `{ asset: "/a/<hash>", size, mime }` — file ingested as a content-addressed asset; caller fetches bytes separately |
| `auto` | Heuristic: UTF-8 text with non-JSON mime → `text`; JSON mime → `json`; everything else → `asset` (not base64) |

**Change from today:** `auto` no longer returns `encoding: "base64"`. Large
binaries become asset references, so agents can pass them around by
reference and streams stay stream-sized. Base64 is only emitted when the
caller explicitly asks for `mode: bytes` (small files, debugging, or
serialising into JSON payloads the caller controls).

### 3.3 Write — one way to send each kind of input

Input:

| Field | Type | Purpose |
|-------|------|---------|
| `drive` | string | Required |
| `path` | string | Required |
| `content` | string | UTF-8 text to write |
| `value` | any | JSON-serialisable value to write (written as UTF-8 JSON) |
| `asset` | string | Asset reference; bytes streamed directly |

Exactly one of `content` / `value` / `asset` is required. No `encoding`
field — each input form is unambiguous:

- `content` is always UTF-8 text.
- `value` is always serialised as JSON with UTF-8 encoding.
- `asset` is always raw bytes.

Binary uploads from user-facing clients **do not go through `dlfs:write`**.
They go through the WebDAV transport (`PUT /dlfs/{drive}/{path}`) with raw
bytes. Agents that need to materialise binary content from workspace data
use `asset` (either a freshly stored asset or an existing reference).

### 3.4 Workspace translation utilities

The adapter ships small, focused helpers that agent operations can share:

- `MimeUtils.guess(String name)` — extension-based MIME lookup.
- `MimeUtils.sniff(byte[] head)` — magic-byte sniff.
- `TextDetect.isLikelyText(byte[])` — the current heuristic, kept.
- `JsonCodec.parse(byte[])` / `JsonCodec.encode(ACell)` — parse a file as
  a workspace value; serialise a workspace value to UTF-8 JSON bytes.
- `AssetIngest.fromPath(Path) → Asset` — stream a file into the asset
  store, return the handle.

Higher-level adapters (vault, agent workspace sync, import/export flows)
call these. DLFS (Layer 1) does not.

### 3.5 Auth and per-user drives

Unchanged from today: `RequestContext` carries the caller DID; each user
has their own cursor into `:dlfs → <DID> → :value`. The only new work is
extending this to cover Layer 2 requests (see §2.2).

## Migration From Current State

Small, ordered, each step independently testable:

1. **Revert recent additions.**
   - `dlfs/write.json`: remove the `encoding` property and the
     "utf-8 / base64" language.
   - `vault/write.json`: same.
   - `DLFSAdapter.handleWrite`: already reverted to text/asset only — keep
     as-is.

2. **Fix the read asymmetry.**
   - `DLFSAdapter.handleRead`: default `mode: auto` returns
     `text` / `json` / `asset` — never base64.
   - Add the explicit `mode` parameter per §3.2. Add `mime` to the output.

3. **Add Content-Type to WebDAV GET.**
   - New `MimeUtils` class (extract current inline logic from VaultView
     into Java).
   - Update the WebDAV GET handler to set `Content-Type`.

4. **Auth for `/dlfs/*`.**
   - Extend `AuthMiddleware`.
   - Update `DLFSWebDAVTest` to pass auth (or to cover both public and
     authenticated modes).

5. **Rewire the UI.**
   - `VaultView.tsx` upload: `PUT /dlfs/health-vault/{path}` with the raw
     `File` as body. Drop base64 and `readAsBase64`.
   - `VaultView.tsx` viewer: for binaries, `<img src="/dlfs/..." />` or an
     `<a href download>` link. Keep text/JSON rendering via `dlfs:read`
     in the appropriate mode.

6. **New `mode` modes.**
   - Add `value`/`asset` write paths (§3.3). `value` is mostly for
     workspace → vault exports; `asset` already works.

Steps 1–3 are the minimum to unblock binary uploads end-to-end and can
land as one change. Steps 4–6 are independent follow-ups.

## What This Is Not

- **Not** a replacement for the asset store. Assets stay
  content-addressed and immutable; DLFS is mutable, path-addressed, and
  per-user.
- **Not** a place for format-specific logic (PDF parsing, image
  thumbnails, CSV flattening). Those belong in dedicated adapters that
  call `dlfs:read` to get bytes.
- **Not** a public byte CDN. Access is authenticated and scoped to the
  caller's drives.

## Open Questions

- Should `dlfs:read` in `auto` mode have a size threshold above which it
  switches to `asset` even for text? (Probably yes — returning a 50 MB
  log file as inline `content` would wreck an LLM's context.)
- Does `value` write need a schema hint, or is shape inference from the
  workspace cell enough?
- WebDAV `If-Match` / `If-None-Match` support for optimistic concurrency
  on long-running agent writes — worth adding, but out of scope for this
  pass.

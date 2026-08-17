---
name: archive
description: Work with zip and jar archive files on a Covia venue — list, extract, and create archives, and read files inside archives without unpacking. Use when an agent needs to inspect, unpack, or bundle archive files.
argument-hint: "<list|extract|zip|peek> <args>"
---

# Archive Files (zip / jar)

**Prerequisite:** The venue must be running and connected as an MCP server (`http://localhost:8080/mcp`). If MCP tools are not available, run `/venue-setup local` first. Archive operations act on the **file adapter's configured roots** — if none are set, the venue provides a single ephemeral `tmp` root (see `/venue-setup`). Check with `file_roots`.

Everything stays inside a configured root: an archive is a jailed `root`+`path` file (or a content-addressed asset / inline bytes), extraction is zip-slip protected, and reads never create an archive.

## Choosing an operation

| Goal | Use |
|------|-----|
| See what's in an archive | `archive_list` |
| Unpack an archive to files | `archive_extract` |
| Bundle files into a zip | `archive_zip` |
| Read one file inside an archive, no unpacking | `file_read` with a `!/` path (peek) |

## `list` — inspect entries

```
archive_list  root=work  path=releases/app.zip
```

Source is exactly one of `root`+`path` (a file), `asset` (a CAS reference), or `bytes` (base64). Returns each entry's `name`, `size`, `compressedSize`, `directory`, and `modified`; `count` is the true total (the entry list is capped, `truncated` flags when more exist).

## `extract` — unpack into a directory

```
archive_extract  root=work  path=releases/app.zip  destRoot=work  destPath=unpacked
```

Writes under `destRoot` (must be a **writable** root) at optional `destPath` (created if needed). Source may instead be `asset=<ref>` or `bytes=<base64>`. Extraction is jailed to the destination — entries that escape via `..` are rejected — and capped against zip bombs. Returns `extracted` (count), `bytes` (total written), and a capped `files` list.

## `zip` — bundle files

To a file:
```
archive_zip  root=work  path=reports  destRoot=work  destPath=reports.zip
```

To a content-addressed **asset** (omit `destRoot`/`destPath`) — the archive becomes a grid artifact you can hand to other operations:
```
archive_zip  root=work  paths=["a.txt","logs/"]  name=bundle.zip
→ { entries, bytes, asset: "did:key:z…/a/<hash>" }
```

`path` (single) or `paths` (array) name files/directories relative to `root`. Entry names preserve the given relative paths (zip -r style); directories are added recursively.

## `peek` — read a file inside an archive without unpacking

`file_read` / `file_list` / `file_stat` / `file_tree` descend into a `.zip`/`.jar` when the path carries a `!/` entry separator right after the archive name (the standard jar-URL form). A `!` anywhere else is an ordinary filename character. The archive must already exist; nothing is unpacked or created.

```
file_read  root=work  path=releases/app.zip!/META-INF/MANIFEST.MF
file_list  root=work  path=releases/app.zip!/            → entries at the archive root
file_stat  root=work  path=releases/app.zip!/config.json
```

File access into archives is **read-only** — `file_write`/`delete`/`mkdir` on a `!/` path are rejected. Create or modify archives with `archive_zip` / `archive_extract`.

## Notes

- **Formats:** zip and jar (a jar is a zip).
- **Sources:** `root`+`path` file · `asset` (CAS reference) · `bytes` (base64) — pick one.
- **Destinations (zip):** a root file, or a CAS asset (default when no `destRoot`).
- **Safety:** paths are jailed to their root; extraction is zip-slip protected and size/entry capped; read paths open existing-only and never fabricate an archive.

package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.crypto.Hashing;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.api.Fields;
import covia.venue.AssetStore;
import covia.venue.RequestContext;

/**
 * Adapter for content-addressed asset operations.
 *
 * <p>Exposes the venue's asset store via MCP: store, get, and list
 * immutable assets identified by the CAD3 hash of their metadata.</p>
 */
public class AssetAdapter extends AAdapter {

	@Override
	public String getName() {
		return "asset";
	}

	@Override
	public String getDescription() {
		return "Store, retrieve, and list content-addressed assets. "
			+ "Assets are immutable, identified by the CAD3 hash of their metadata.";
	}

	@Override
	protected void installAssets() {
		String BASE = "/adapters/asset/";
		installAsset("asset/store",   BASE + "store.json");
		installAsset("asset/get",     BASE + "get.json");
		installAsset("asset/content", BASE + "content.json");
		installAsset("asset/list",    BASE + "list.json");
		installAsset("asset/pin",     BASE + "pin.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Asset operations require an authenticated caller"));
		}

		try {
			return switch (getSubOperation(meta)) {
				case "store"   -> CompletableFuture.completedFuture(handleStore(input, ctx));
				case "get"     -> CompletableFuture.completedFuture(handleGet(input, ctx));
				case "content" -> CompletableFuture.completedFuture(handleContent(input, ctx));
				case "list"    -> CompletableFuture.completedFuture(handleList(input, ctx));
				case "pin"     -> CompletableFuture.completedFuture(handlePin(input, ctx));
				default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown asset operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	@SuppressWarnings("unchecked")
	private ACell handleStore(ACell input, RequestContext ctx) {
		ACell metaCell = RT.getIn(input, Fields.METADATA);
		// Accept metadata as a JSON object or a JSON string (parsed on the fly).
		// NB: instanceof check on the parsed value is required because RT.ensureMap(null)
		// returns an empty map — we need to reject non-object JSON (e.g. "[]", "null").
		AMap<AString, ACell> metadata;
		if (metaCell instanceof AMap) {
			metadata = (AMap<AString, ACell>) metaCell;
		} else if (metaCell instanceof AString s) {
			ACell parsed;
			try {
				parsed = convex.core.util.JSON.parse(s.toString());
			} catch (Exception e) {
				throw new IllegalArgumentException("metadata string is not valid JSON: " + e.getMessage());
			}
			metadata = (parsed instanceof AMap) ? (AMap<AString, ACell>) parsed : null;
		} else {
			metadata = null;
		}
		if (metadata == null) {
			throw new IllegalArgumentException("metadata is required and must be a JSON object or JSON string");
		}

		// Optional content — two explicit paths, no inference:
		//   "content" = hex Blob ("0x...")
		//   "contentText" = plain text string (auto-encoded to UTF-8 Blob)
		ACell contentCell = RT.getIn(input, Fields.CONTENT);
		ACell contentTextCell = RT.getIn(input, K_CONTENT_TEXT);
		if (contentCell != null && contentTextCell != null) {
			throw new IllegalArgumentException("Provide content (hex Blob) or contentText (string), not both");
		}
		ABlob content;
		if (contentTextCell != null) {
			AString text = RT.ensureString(contentTextCell);
			if (text == null) throw new IllegalArgumentException("contentText must be a string");
			content = Blob.wrap(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} else {
			content = toBlob(contentCell);
		}

		// If content provided, validate or inject content.sha256 in metadata
		if (content != null) {
			Hash contentHash = Hashing.sha256(content.getBytes());
			AString hashHex = Strings.create(contentHash.toHexString());

			AString existingHash = RT.ensureString(RT.getIn(metadata, Fields.CONTENT, Fields.SHA256));
			if (existingHash != null) {
				// Validate: declared hash must match actual content
				if (!existingHash.equals(hashHex)) {
					throw new IllegalArgumentException(
						"Content hash mismatch. Declared: " + existingHash + ", Actual: " + hashHex);
				}
			} else {
				// Inject content.sha256 into metadata
				AMap<AString, ACell> contentMeta = RT.ensureMap(RT.getIn(metadata, Fields.CONTENT));
				if (contentMeta == null) contentMeta = Maps.empty();
				contentMeta = contentMeta.assoc(Fields.SHA256, hashHex);
				metadata = metadata.assoc(Fields.CONTENT, contentMeta);
			}
		}

		AString jsonString = JSON.printPretty(metadata);
		Hash id = engine.storeUserAsset(jsonString, content, ctx);

		// Return full DID URL: did:key:zCaller.../a/<hash>
		AString didUrl = ctx.getCallerDID().append("/a/" + id.toHexString());
		return Maps.of(
			Fields.ID, didUrl,
			Fields.STORED, CVMBool.TRUE);
	}

	/**
	 * Converts a content value to a Blob. Strict typing:
	 * - null → null (no content)
	 * - ABlob → returned as-is (native Blob)
	 * - AString "0x..." → parsed as hex to Blob
	 * - Anything else → error
	 */
	private static ABlob toBlob(ACell cell) {
		if (cell == null) return null;
		if (cell instanceof ABlob b) return b;
		if (cell instanceof AString s) {
			String str = s.toString();
			if (str.startsWith("0x") || str.startsWith("0X")) {
				Blob b = Blob.fromHex(str.substring(2));
				if (b != null) return b;
			}
			throw new IllegalArgumentException("content string must be hex-encoded (0x...), got: " + str.substring(0, Math.min(20, str.length())) + "...");
		}
		throw new IllegalArgumentException("content must be a Blob (hex string 0x...), got: " + cell.getClass().getSimpleName());
	}

	private static final AString K_EXISTS = Strings.intern("exists");
	private static final AString K_CONTENT_TEXT = Strings.intern("contentText");
	private static final AString K_MAX_SIZE = Strings.intern("maxSize");
	private static final AString K_TRUNCATED = Strings.intern("truncated");
	private static final AString K_SIZE = Strings.intern("size");

	/** Default max content size: 1 MB */
	private static final long DEFAULT_MAX_SIZE = 1_000_000;

	/**
	 * Parses an asset hash from an ID string. Accepts bare hash, a/<hash>,
	 * /a/<hash>, or did:key:.../a/<hash> formats.
	 */
	private static Hash parseAssetId(AString idStr) {
		if (idStr == null) return null;
		String s = idStr.toString();
		// Strip DID prefix if present: did:key:z6Mk.../a/<hash> → <hash>
		int aPos = s.indexOf("/a/");
		if (aPos >= 0) {
			s = s.substring(aPos + 3);
		} else if (s.startsWith("a/")) {
			// Bare a/<hash> form — matches the user-namespace convention
			// used by other prefixes (w/, o/, etc.) and the asset_list tool
			// description.
			s = s.substring(2);
		}
		return Hash.parse(s);
	}

	@SuppressWarnings("unchecked")
	private ACell handleGet(ACell input, RequestContext ctx) {
		AString idStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (idStr == null) throw new IllegalArgumentException("id is required");

		// Hash-form refs go through the CAS record (preserves the canonical
		// metadata bytes). Other forms walk the universal resolver.
		AMap<AString, ACell> meta = null;
		Hash hash = parseAssetId(idStr);
		if (hash != null) {
			AVector<?> record = engine.getAssetRecord(hash, ctx);
			if (record != null) {
				meta = RT.ensureMap(record.get(AssetStore.POS_META));
			}
		} else {
			ACell value = engine.resolvePath(idStr, ctx);
			if (value instanceof AMap) meta = (AMap<AString, ACell>) value;
		}

		if (meta == null) {
			return Maps.of(Fields.ID, idStr, K_EXISTS, CVMBool.FALSE);
		}

		return Maps.of(
			Fields.ID, idStr,
			K_EXISTS, CVMBool.TRUE,
			Fields.VALUE, meta);
	}

	@SuppressWarnings("unchecked")
	private ACell handleContent(ACell input, RequestContext ctx) {
		AString idStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (idStr == null) throw new IllegalArgumentException("id is required");

		// Locate the CAS record for the source. Hash-form refs name the record
		// directly. For non-hash refs we resolve the path, derive the CAD3 hash
		// of the resulting metadata, and look up the same record by hash —
		// non-CAS workspace values that have never been stored as assets won't
		// have a record, and the call returns exists: false.
		AVector<?> record = null;
		Hash hash = parseAssetId(idStr);
		if (hash != null) {
			record = engine.getAssetRecord(hash, ctx);
		} else {
			ACell value = engine.resolvePath(idStr, ctx);
			if (value instanceof AMap) {
				Hash derived = ((AMap<AString, ACell>) value).getHash();
				record = engine.getAssetRecord(derived, ctx);
			}
		}

		if (record == null) {
			return Maps.of(Fields.ID, idStr, K_EXISTS, CVMBool.FALSE);
		}

		ACell content = record.get(AssetStore.POS_CONTENT);
		if (content == null) {
			// Asset exists but has no content payload
			return Maps.of(Fields.ID, idStr, K_EXISTS, CVMBool.TRUE);
		}

		// Size guard — consistent with covia:read maxSize pattern
		long maxSize = DEFAULT_MAX_SIZE;
		ACell maxSizeCell = RT.getIn(input, K_MAX_SIZE);
		if (maxSizeCell instanceof CVMLong l) maxSize = Math.max(1, l.longValue());

		// Content is always a Blob — check byte count
		long size = (content instanceof ABlob b) ? b.count() : content.getMemorySize();
		if (size > maxSize) {
			return Maps.of(
				Fields.ID, idStr,
				K_EXISTS, CVMBool.TRUE,
				K_TRUNCATED, CVMBool.TRUE,
				K_SIZE, CVMLong.create(size));
		}

		// Returns Blob, which JSON-serialises to "0x..." hex string
		return Maps.of(
			Fields.ID, idStr,
			K_EXISTS, CVMBool.TRUE,
			Fields.VALUE, content);
	}

	@SuppressWarnings("unchecked")
	private ACell handleList(ACell input, RequestContext ctx) {
		long offset = 0, limit = 100;
		ACell offsetCell = RT.getIn(input, Fields.OFFSET);
		if (offsetCell instanceof CVMLong l) offset = Math.max(0, l.longValue());
		ACell limitCell = RT.getIn(input, Fields.LIMIT);
		if (limitCell instanceof CVMLong l) limit = Math.max(1, Math.min(1000, l.longValue()));

		AString typeFilter = RT.ensureString(RT.getIn(input, Fields.TYPE));

		// List user's assets + venue-level assets (venue assets are public infrastructure)
		AMap<ABlob, AVector<?>> allAssets = engine.getAssets();
		if (ctx != null && ctx.getCallerDID() != null) {
			covia.venue.User user = engine.getVenueState().users().get(ctx.getCallerDID());
			if (user != null) {
				AMap<ABlob, AVector<?>> userAssets = user.assets().getAll();
				if (userAssets != null) {
					if (allAssets != null) allAssets = allAssets.merge(userAssets);
					else allAssets = userAssets;
				}
			}
		}
		long rawTotal = (allAssets != null) ? allAssets.count() : 0;

		AVector<ACell> items = Vectors.empty();
		long matched = 0;
		long emitted = 0;

		if (allAssets != null) {
			for (long i = 0; i < rawTotal; i++) {
				var entry = allAssets.entryAt(i);
				if (entry == null) continue;
				AVector<ACell> record = (AVector<ACell>) entry.getValue();
				AMap<AString, ACell> meta = RT.ensureMap(record.get(AssetStore.POS_META));
				if (meta == null) continue;

				// Apply type filter if specified
				if (typeFilter != null) {
					AString assetType = RT.ensureString(meta.get(Fields.TYPE));
					if (!typeFilter.equals(assetType)) continue;
				}

				matched++;

				// Skip entries before offset
				if (matched <= offset) continue;

				// Stop collecting after limit
				if (emitted >= limit) continue;

				Hash h = Hash.wrap(entry.getKey().getBytes());
				AMap<AString, ACell> summary = Maps.of(
					Fields.ID, Strings.create(h.toHexString()),
					Fields.NAME, meta.get(Fields.NAME),
					Fields.TYPE, meta.get(Fields.TYPE),
					Fields.DESCRIPTION, meta.get(Fields.DESCRIPTION));
				items = items.conj(summary);
				emitted++;
			}
		}

		// Total reflects filtered count when filter is active, raw count otherwise
		long total = (typeFilter != null) ? matched : rawTotal;

		return Maps.of(
			Fields.ITEMS, items,
			Fields.TOTAL, CVMLong.create(total),
			Fields.OFFSET, CVMLong.create(offset),
			Fields.LIMIT, CVMLong.create(limit));
	}

	@SuppressWarnings("unchecked")
	private ACell handlePin(ACell input, RequestContext ctx) {
		// Accept `path` (preferred) or `id` (deprecated alias) for one cycle.
		AString pathStr = RT.ensureString(RT.getIn(input, Fields.PATH));
		if (pathStr == null) pathStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (pathStr == null) throw new IllegalArgumentException("path is required");

		AString metaString;
		ACell content = null;

		// If the source is a hash-form reference (bare hex, /a/<hash>, or
		// did:.../a/<hash>), look up the existing asset record so we preserve
		// its content payload as well as the metadata. Resolving these via the
		// generic path resolver would only return the metadata.
		Hash existingHash = parseAssetId(pathStr);
		if (existingHash != null) {
			AVector<?> record = engine.getAssetRecord(existingHash, ctx);
			if (record == null) throw new IllegalArgumentException("Asset not found: " + pathStr);
			metaString = RT.ensureString(record.get(AssetStore.POS_JSON));
			content = record.get(AssetStore.POS_CONTENT);
		} else {
			// Non-hash reference: walk the universal resolver. The resolved
			// value is treated as inline metadata; non-asset paths produce
			// content-less pins.
			ACell value = engine.resolvePath(pathStr, ctx);
			if (value == null) throw new IllegalArgumentException("Path not found: " + pathStr);
			if (!(value instanceof AMap)) {
				throw new IllegalArgumentException(
					"Cannot pin non-map value at " + pathStr
					+ " (got " + value.getClass().getSimpleName() + ")");
			}
			metaString = JSON.printPretty((AMap<AString, ACell>) value);
		}

		// Store into the caller's /a/ namespace.
		Hash hash = engine.storeUserAsset(metaString, content, ctx);

		// Return the caller's DID URL plus the bare hex hash. The path is
		// directly usable as input to other read-side ops; the hash is
		// convenient for version comparison.
		AString didUrl = ctx.getCallerDID().append("/a/" + hash.toHexString());
		return Maps.of(
			Fields.PATH, didUrl,
			Strings.intern("hash"), Strings.create(hash.toHexString()));
	}
}

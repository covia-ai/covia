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
		installAsset(BASE + "store.json");
		installAsset(BASE + "get.json");
		installAsset(BASE + "content.json");
		installAsset(BASE + "list.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Asset operations require an authenticated caller"));
		}

		try {
			return switch (getSubOperation(meta)) {
				case "store"   -> CompletableFuture.completedFuture(handleStore(input));
				case "get"     -> CompletableFuture.completedFuture(handleGet(input));
				case "content" -> CompletableFuture.completedFuture(handleContent(input));
				case "list"    -> CompletableFuture.completedFuture(handleList(input));
				default -> CompletableFuture.failedFuture(
					new IllegalArgumentException("Unknown asset operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private ACell handleStore(ACell input) {
		AMap<AString, ACell> metadata = RT.ensureMap(RT.getIn(input, Fields.METADATA));
		if (metadata == null) {
			throw new IllegalArgumentException("metadata is required and must be a JSON object");
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
		Hash id = engine.storeAsset(jsonString, content);

		return Maps.of(
			Fields.ID, Strings.create(id.toHexString()),
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

	private ACell handleGet(ACell input) {
		AString idStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (idStr == null) throw new IllegalArgumentException("id is required");

		Hash hash = Hash.parse(idStr);
		if (hash == null) throw new IllegalArgumentException("Invalid asset ID format: " + idStr);

		AMap<AString, ACell> meta = engine.getMetaValue(hash);
		if (meta == null) {
			return Maps.of(Fields.ID, idStr, K_EXISTS, CVMBool.FALSE);
		}

		// Consistent with covia:read — return {exists, value}
		return Maps.of(
			Fields.ID, idStr,
			K_EXISTS, CVMBool.TRUE,
			Fields.VALUE, meta);
	}

	private ACell handleContent(ACell input) {
		AString idStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (idStr == null) throw new IllegalArgumentException("id is required");

		Hash hash = Hash.parse(idStr);
		if (hash == null) throw new IllegalArgumentException("Invalid asset ID format: " + idStr);

		AVector<?> record = engine.getVenueState().assets().getRecord(hash);
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
	private ACell handleList(ACell input) {
		long offset = 0, limit = 100;
		ACell offsetCell = RT.getIn(input, Fields.OFFSET);
		if (offsetCell instanceof CVMLong l) offset = Math.max(0, l.longValue());
		ACell limitCell = RT.getIn(input, Fields.LIMIT);
		if (limitCell instanceof CVMLong l) limit = Math.max(1, Math.min(1000, l.longValue()));

		AString typeFilter = RT.ensureString(RT.getIn(input, Fields.TYPE));

		AMap<ABlob, AVector<?>> allAssets = engine.getAssets();
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
}

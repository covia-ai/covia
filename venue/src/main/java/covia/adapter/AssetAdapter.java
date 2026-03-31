package covia.adapter;

import java.util.concurrent.CompletableFuture;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
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
				case "store" -> CompletableFuture.completedFuture(handleStore(input));
				case "get"   -> CompletableFuture.completedFuture(handleGet(input));
				case "list"  -> CompletableFuture.completedFuture(handleList(input));
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

		// Optional content — stored alongside metadata but separate
		ACell content = RT.getIn(input, Fields.CONTENT);

		AString jsonString = JSON.printPretty(metadata);
		Hash id = engine.storeAsset(jsonString, content);

		return Maps.of(
			Fields.ID, Strings.create(id.toHexString()),
			Fields.STORED, CVMBool.TRUE);
	}

	private ACell handleGet(ACell input) {
		AString idStr = RT.ensureString(RT.getIn(input, Fields.ID));
		if (idStr == null) throw new IllegalArgumentException("id is required");

		Hash hash = Hash.parse(idStr);
		if (hash == null) throw new IllegalArgumentException("Invalid asset ID format: " + idStr);

		AVector<?> record = engine.getVenueState().assets().getRecord(hash);
		if (record == null) throw new IllegalArgumentException("Asset not found: " + idStr);

		AMap<AString, ACell> meta = RT.ensureMap(record.get(AssetStore.POS_META));
		ACell content = record.get(AssetStore.POS_CONTENT);

		AMap<AString, ACell> result = Maps.of(
			Fields.ID, idStr,
			Fields.METADATA, meta);
		if (content != null) {
			result = result.assoc(Fields.CONTENT, content);
		}
		return result;
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
		long total = (allAssets != null) ? allAssets.count() : 0;

		AVector<ACell> items = Vectors.empty();
		long skipped = 0;
		long emitted = 0;

		if (allAssets != null) {
			for (long i = 0; i < total && emitted < limit; i++) {
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

				// Skip entries before offset
				if (skipped < offset) {
					skipped++;
					continue;
				}

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

		return Maps.of(
			Fields.ITEMS, items,
			Fields.TOTAL, CVMLong.create(total),
			Fields.OFFSET, CVMLong.create(offset),
			Fields.LIMIT, CVMLong.create(limit));
	}
}

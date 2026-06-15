package covia.adapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.RequestContext;

/**
 * Generic per-user memory — a simple, always-in-context numbered list.
 *
 * <p>Memory is a flat, ordered list of short entries the agent should always
 * know about the user (preferences, key facts, surfaced context). It is meant
 * to be rendered into the agent's system context on every turn (via an entry in
 * the agent's {@code config.context}) and edited by <b>position number</b> —
 * exactly like an assistant "user memory": {@code remember} appends,
 * {@code update <n>} rewrites an item, {@code forget <n>} removes one.</p>
 *
 * <h3>Storage</h3>
 * <p>The list is a single {@code AVector} stored at a configurable workspace
 * path (default {@code w/memory}). Every mutation rewrites the <b>whole vector</b>
 * via {@code covia:write}, which is a whole-value LWW replace
 * ({@link covia.lattice.LWWWrapperLattice}) — so removals are durable and never
 * re-materialise (this also avoids the per-key delete concern in
 * GetMine-ai/demo#134). Per-user DID scoping comes from the {@code covia:*} ops
 * for free; this adapter holds no identity logic.</p>
 *
 * <h3>Numbering</h3>
 * <p>Display numbers are 1-based positions in the vector. {@code update}/{@code forget}
 * take {@code n} and resolve it against the current list order — the same order
 * {@code recall} renders, so the number the agent sees is the number it edits.</p>
 *
 * <h3>Operations</h3>
 * <ul>
 *   <li>{@code memory:recall} — render the full numbered list as a text block
 *       (or null when empty). The context assemble-op.</li>
 *   <li>{@code memory:remember} — append an item.</li>
 *   <li>{@code memory:update} — replace item {@code n}.</li>
 *   <li>{@code memory:forget} — remove item {@code n}.</li>
 * </ul>
 */
public class UserMemoryAdapter extends AAdapter {

	public static final Logger log = LoggerFactory.getLogger(UserMemoryAdapter.class);

	/** Default workspace path for the memory list when none is supplied. */
	private static final String DEFAULT_PATH = "w/memory";

	// Input / output keys
	private static final AString K_PATH       = Strings.intern("path");
	private static final AString K_VALUE      = Strings.intern("value");
	private static final AString K_TEXT       = Strings.intern("text");
	private static final AString K_TS         = Strings.intern("ts");
	private static final AString K_UPDATED    = Strings.intern("updated");
	private static final AString K_N          = Strings.intern("n");
	private static final AString K_COUNT      = Strings.intern("count");
	private static final AString K_REMEMBERED = Strings.intern("remembered");
	private static final AString K_FORGOTTEN  = Strings.intern("forgotten");
	private static final AString K_UPDATED_OK = Strings.intern("updated");

	@Override
	public String getName() {
		return "memory";
	}

	@Override
	public String getDescription() {
		return "Generic per-user memory: a simple, always-in-context numbered list of things to remember "
			+ "about the user. recall renders the numbered list as system context; remember appends an item; "
			+ "update and forget edit/remove an item by its number. Stored durably in the user's workspace.";
	}

	@Override
	protected void installAssets() {
		String BASE = "/adapters/memory/";
		installAsset("memory/recall",   BASE + "recall.json");
		installAsset("memory/remember", BASE + "remember.json");
		installAsset("memory/update",   BASE + "update.json");
		installAsset("memory/forget",   BASE + "forget.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		// Authentication required: memory is always scoped to a calling identity.
		if (ctx.getCallerDID() == null) {
			return CompletableFuture.failedFuture(new RuntimeException("Authentication required"));
		}
		try {
			return switch (getSubOperation(meta)) {
				case "recall"   -> CompletableFuture.completedFuture(handleRecall(ctx, input));
				case "remember" -> CompletableFuture.completedFuture(handleRemember(ctx, input));
				case "update"   -> CompletableFuture.completedFuture(handleUpdate(ctx, input));
				case "forget"   -> CompletableFuture.completedFuture(handleForget(ctx, input));
				default -> CompletableFuture.failedFuture(
					new RuntimeException("Unknown memory operation: " + getSubOperation(meta)));
			};
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ========== recall — render the numbered list ==========

	private ACell handleRecall(RequestContext ctx, ACell input) {
		String path = path(input);
		AVector<ACell> list = readList(ctx, path);
		if (list.isEmpty()) return null; // empty → context entry is skipped (no empty heading)

		// Return the bare numbered list. The heading is supplied by the caller:
		// for config.context, the entry's `label` becomes "[Context: <label>]" via
		// ContextLoader — so recall must NOT prepend its own heading (avoids the
		// double-label "[Context: ...]\n<own heading>:").
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < list.count(); i++) {
			if (i > 0) sb.append("\n");
			sb.append(i + 1).append(". ").append(textOf(list.get(i)));
		}
		return Strings.create(sb.toString());
	}

	// ========== remember — append an item ==========

	private ACell handleRemember(RequestContext ctx, ACell input) {
		String path = path(input);
		AString text = RT.ensureString(RT.getIn(input, "text"));
		if (text == null) throw new RuntimeException("memory:remember requires 'text'");

		AVector<ACell> list = readList(ctx, path);
		AMap<AString, ACell> entry = Maps.of(K_TEXT, text, K_TS, CVMLong.create(System.currentTimeMillis()));
		list = list.conj(entry);
		writeList(ctx, path, list);

		return Maps.of(K_REMEMBERED, CVMBool.TRUE, K_N, CVMLong.create(list.count()), K_COUNT, CVMLong.create(list.count()));
	}

	// ========== update — replace item n ==========

	private ACell handleUpdate(RequestContext ctx, ACell input) {
		String path = path(input);
		AString text = RT.ensureString(RT.getIn(input, "text"));
		if (text == null) throw new RuntimeException("memory:update requires 'text'");

		AVector<ACell> list = readList(ctx, path);
		long idx = index(input, list.count());

		ACell prev = list.get(idx);
		long ts = (RT.getIn(prev, "ts") instanceof CVMLong l) ? l.longValue() : System.currentTimeMillis();
		AMap<AString, ACell> entry = Maps.of(
			K_TEXT, text,
			K_TS, CVMLong.create(ts),
			K_UPDATED, CVMLong.create(System.currentTimeMillis()));
		list = list.assoc(idx, entry);
		writeList(ctx, path, list);

		return Maps.of(K_UPDATED_OK, CVMBool.TRUE, K_N, CVMLong.create(idx + 1), K_COUNT, CVMLong.create(list.count()));
	}

	// ========== forget — remove item n ==========

	private ACell handleForget(RequestContext ctx, ACell input) {
		String path = path(input);
		AVector<ACell> list = readList(ctx, path);
		long idx = index(input, list.count());

		AVector<ACell> out = Vectors.empty();
		for (long i = 0; i < list.count(); i++) {
			if (i != idx) out = out.conj(list.get(i));
		}
		writeList(ctx, path, out);

		return Maps.of(K_FORGOTTEN, CVMBool.TRUE, K_N, CVMLong.create(idx + 1), K_COUNT, CVMLong.create(out.count()));
	}

	// ========== helpers ==========

	private static String path(ACell input) {
		AString p = RT.ensureString(RT.getIn(input, "path"));
		return (p != null) ? p.toString() : DEFAULT_PATH;
	}

	/** Resolve a 1-based 'n' input to a 0-based index, validating range. */
	private static long index(ACell input, long count) {
		ACell nCell = RT.getIn(input, "n");
		if (!(nCell instanceof CVMLong l)) throw new RuntimeException("requires a numeric 'n' (1-based item number)");
		long n = l.longValue();
		if (n < 1 || n > count) {
			throw new RuntimeException("item " + n + " out of range (list has " + count + " item" + (count == 1 ? "" : "s") + ")");
		}
		return n - 1;
	}

	/** The text shown for an entry — supports {text:...} maps and plain strings. */
	private static String textOf(ACell entry) {
		if (entry instanceof AMap) {
			ACell t = RT.getIn(entry, "text");
			if (t != null) return t.toString();
		}
		return (entry != null) ? entry.toString() : "";
	}

	@SuppressWarnings("unchecked")
	private AVector<ACell> readList(RequestContext ctx, String path) {
		ACell read = invokeCovia("v/ops/covia/read", Maps.of(K_PATH, Strings.create(path)), ctx);
		ACell value = RT.getIn(read, "value");
		return (value instanceof AVector) ? (AVector<ACell>) value : Vectors.empty();
	}

	private void writeList(RequestContext ctx, String path, AVector<ACell> list) {
		invokeCovia("v/ops/covia/write", Maps.of(K_PATH, Strings.create(path), K_VALUE, list), ctx);
	}

	private ACell invokeCovia(String op, ACell input, RequestContext ctx) {
		try {
			return engine.jobs().invokeInternal(op, input, ctx).get(10_000, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			throw new RuntimeException("memory: internal " + op + " failed: " + e.getMessage(), e);
		}
	}
}

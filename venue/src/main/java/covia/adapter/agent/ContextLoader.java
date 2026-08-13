package covia.adapter.agent;

import java.nio.charset.StandardCharsets;

import convex.auth.ucan.Capability;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.util.CellExplorer;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AssetAdapter;
import covia.adapter.CoviaAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Resolves context entries into system messages for agent LLM context.
 *
 * <p>Context entries can be:</p>
 * <ul>
 *   <li>Literal text strings</li>
 *   <li>Workspace path references ({@code w/docs/rules})</li>
 *   <li>Asset references (hex hash, {@code /a/}, {@code /o/}, DID URL, registered name)</li>
 *   <li>Job result references ({@code {"job": "0x..."}}</li>
 *   <li>Grid operation calls ({@code {"op": "v/ops/covia/read", "input": {...}}})</li>
 *   <li>Map entries with {@code ref}, {@code text}, {@code label}, {@code required} fields</li>
 * </ul>
 *
 * <p>See {@code venue/docs/AGENT_CONTEXT.md} for the full design.</p>
 */
public class ContextLoader {

	private static final AString K_ROLE    = Strings.intern("role");
	private static final AString K_CONTENT = Strings.intern("content");
	private static final AString ROLE_SYSTEM = Strings.intern("system");

	private static final AString K_REF      = Strings.intern("ref");
	private static final AString K_TEXT     = Strings.intern("text");
	private static final AString K_LABEL    = Strings.intern("label");
	private static final AString K_REQUIRED = Strings.intern("required");
	private static final AString K_OP       = Strings.intern("op");
	private static final AString K_INPUT    = Strings.intern("input");
	private static final AString K_JOB      = Strings.intern("job");
	private static final AString K_PATH     = Strings.intern("path");

	private final Engine engine;
	private CellExplorer explorer;

	public ContextLoader(Engine engine) {
		this.engine = engine;
	}

	/**
	 * Sets a CellExplorer for budget-controlled JSON5 rendering of lattice values.
	 * When set, workspace paths, job outputs, and operation results are rendered
	 * via the explorer instead of raw {@code toString()}.
	 */
	public void setCellExplorer(CellExplorer explorer) {
		this.explorer = explorer;
	}

	/**
	 * Renders a CVM value as a string for inclusion in LLM context.
	 *
	 * <p>Plain strings are returned directly — CellExplorer wraps strings
	 * in JSON5 quotes and escapes newlines, which destroys readable text
	 * content like markdown documents. CellExplorer is only used for
	 * structured values (maps, vectors, etc.) where budget-controlled
	 * rendering is valuable.</p>
	 */
	public String renderValue(ACell value) {
		if (value instanceof AString s) return s.toString();
		if (explorer != null) return explorer.explore(value).toString();
		return value.toString();
	}

	/**
	 * Resolves a vector of context entries into a vector of system messages.
	 *
	 * @param entries Context entries (strings or maps)
	 * @param ctx Request context (caller identity for namespace scoping)
	 * @return Vector of system message maps ({role: "system", content: "..."})
	 */
	public AVector<ACell> resolve(AVector<ACell> entries, RequestContext ctx) {
		if (entries == null || entries.count() == 0) return Vectors.empty();

		AVector<ACell> messages = Vectors.empty();
		for (long i = 0; i < entries.count(); i++) {
			ACell entry = entries.get(i);
			ACell msg = resolveEntry(entry, ctx);
			if (msg != null) {
				messages = messages.conj(msg);
			}
		}
		return messages;
	}

	/**
	 * Resolves a single context entry into a system message, or null if
	 * the entry cannot be resolved and is not required.
	 */
	public ACell resolveEntry(ACell entry, RequestContext ctx) {
		if (entry instanceof AString s) {
			return resolveStringEntry(s, ctx);
		} else if (entry instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) entry;
			return resolveMapEntry(map, ctx);
		}
		return null;
	}

	/**
	 * Resolves a string context entry. Interprets as workspace path, asset
	 * reference, or literal text based on prefix.
	 */
	ACell resolveStringEntry(AString ref, RequestContext ctx) {
		String label = deriveLabel(ref.toString());
		try {
			String content = resolveReference(ref, ctx);
			if (content == null) return null;            // absent/empty → skip
			return systemMessage(label, content);
		} catch (RuntimeException e) {
			// String entries carry no `required` flag — surface the failure
			// visibly so the LLM knows this context source is broken.
			return errorMessage(label, rootMessage(e));
		}
	}

	/**
	 * Resolves a map context entry with explicit fields.
	 */
	ACell resolveMapEntry(AMap<AString, ACell> map, RequestContext ctx) {
		AString label = RT.ensureString(map.get(K_LABEL));
		boolean required = convex.core.data.prim.CVMBool.TRUE.equals(map.get(K_REQUIRED));

		// Grid operation entry
		AString op = RT.ensureString(map.get(K_OP));
		if (op != null) {
			return resolveOpEntry(op, map.get(K_INPUT), label, required, ctx);
		}

		// Job result entry
		AString jobId = RT.ensureString(map.get(K_JOB));
		if (jobId != null) {
			return resolveJobEntry(jobId, RT.ensureString(map.get(K_PATH)), label, required, ctx);
		}

		// Literal text entry
		AString text = RT.ensureString(map.get(K_TEXT));
		if (text != null) {
			String labelStr = (label != null) ? label.toString() : null;
			return systemMessage(labelStr, text.toString());
		}

		// Reference entry
		AString ref = RT.ensureString(map.get(K_REF));
		if (ref != null) {
			String labelStr = (label != null) ? label.toString() : deriveLabel(ref.toString());
			try {
				String content = resolveReference(ref, ctx);
				if (content == null) {
					if (required) throw new RuntimeException("Required context entry not found: " + ref);
					return null;                          // absent/empty → skip
				}
				return systemMessage(labelStr, content);
			} catch (RuntimeException e) {
				if (required) throw e;
				return errorMessage(labelStr, rootMessage(e));   // errored → visible
			}
		}

		return null;
	}

	/**
	 * Resolves a reference string to text content. Returns null if not resolvable.
	 */
	String resolveReference(AString ref, RequestContext ctx) {
		if (ref == null) return null;
		String refStr = ref.toString();

		// Workspace/namespace path
		if (isNamespacePath(refStr)) {
			return resolveWorkspacePath(refStr, ctx);
		}

		// Try as asset reference (hash, /a/, /o/, DID URL, registered name)
		return resolveAssetContent(ref, ctx);
	}

	/**
	 * Returns true if the string starts with a known namespace prefix.
	 */
	public static boolean isNamespacePath(String ref) {
		if (ref == null) return false;
		// A leading slash is optional sugar (mirrors Engine.resolvePath): "/w/x"
		// resolves like "w/x". Normalise it before matching namespace prefixes —
		// without this, a context entry like "/w/docs/rules" fell through to
		// literal text instead of resolving the workspace path.
		String s = ref.startsWith("/") ? ref.substring(1) : ref;
		return s.startsWith("w/") || s.startsWith("g/") || s.startsWith("o/")
			|| s.startsWith("j/") || s.startsWith("s/") || s.startsWith("h/")
			|| s.startsWith("n/") || s.startsWith("t/") || s.startsWith("c/");
	}

	/**
	 * Returns true if the string looks like an asset reference rather than literal text.
	 *
	 * <p>References include hex hashes, /a/ and /o/ paths, DID URLs, lattice
	 * namespace paths (w/, g/, j/, s/, h/, n/, t/, o/), and venue catalog
	 * paths under /v/. Strings with spaces are treated as literal text.</p>
	 */
	public static boolean isAssetReference(String ref) {
		if (ref == null || ref.isEmpty() || ref.contains(" ")) return false;
		if (ref.startsWith("/a/") || ref.startsWith("/o/")) return true;
		if (ref.startsWith("did:")) return true;
		if (ref.length() == 64 && ref.matches("[0-9a-fA-F]+")) return true; // hex hash
		// Lattice namespace paths — leading slash optional (mirrors resolvePath).
		// a/ is the bare asset-ref form (matches AssetAdapter.parseAssetId).
		String s = ref.startsWith("/") ? ref.substring(1) : ref;
		if (s.startsWith("a/") || s.startsWith("w/") || s.startsWith("o/") || s.startsWith("g/")
			|| s.startsWith("j/") || s.startsWith("s/") || s.startsWith("h/")
			|| s.startsWith("n/") || s.startsWith("t/") || s.startsWith("c/")
			|| s.startsWith("v/")) return true;
		return false;
	}

	/**
	 * Reads a value from the user's lattice namespace using the internal
	 * cursor-based path resolution (same as CoviaAdapter.readPath).
	 */
	String resolveWorkspacePath(String path, RequestContext ctx) {
		try {
			requireReadAccess(Strings.create(path), ctx);
			CoviaAdapter covia = (CoviaAdapter) engine.getAdapter("covia");
			if (covia == null) return null;
			ACell value = covia.readContextValue(ctx, Strings.create(path));
			if (value == null) return null;              // path absent → caller skips
			return renderValue(value);
		} catch (RuntimeException e) {
			throw e;                                      // genuine read error → caller makes it visible
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Resolves an asset reference and extracts text content.
	 *
	 * <p>Layered: the venue's universal content resolution first
	 * ({@link Engine#resolveContent} — CAS blob, {@code content.dlfs}
	 * pinned/live bindings, provider refs; handles content-only artifacts
	 * that have no {@code operation} field), then the resolved value's
	 * {@code description} (asset-metadata maps) or its rendered form (data
	 * values, e.g. {@code v/info/...}), then {@link Engine#resolveAsset}
	 * for the remote-DID definition-fetch path. Null when nothing usable
	 * is found → caller skips.</p>
	 */
	String resolveAssetContent(AString ref, RequestContext ctx) {
		// If it doesn't look like a reference, treat as literal text
		if (!isAssetReference(ref.toString())) return ref.toString();

		try {
			requireReadAccess(ref, ctx);
			// 1. Content, via the universal resolution chain (UTF-8 decode).
			covia.venue.storage.ContentProvider.Resolved resolved = engine.resolveContent(ref, ctx);
			if (resolved != null && resolved.content() != null) {
				ABlob blob = resolved.content().getBlob();
				if (blob != null && blob.count() > 0) {
					return new String(blob.getBytes(), StandardCharsets.UTF_8);
				}
			}

			// 2. No content — a metadata map falls back to its description
			// (the standard asset convention); any other resolved value
			// renders directly (v/ data paths, catalog entries).
			ACell value = engine.resolvePath(ref, ctx);
			if (value != null) {
				if (value instanceof AMap) {
					AString desc = RT.ensureString(((AMap<?, ?>) value).get(Fields.DESCRIPTION));
					if (desc != null) return desc.toString();
				}
				return renderValue(value);
			}

			// 3. Remote DID definitions (resolveAsset's fetch path) — local
			// resolution found nothing.
			Asset asset = engine.resolveAsset(ref, ctx);
			if (asset != null) {
				AString desc = RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
				if (desc != null) return desc.toString();
			}

			return null;                                  // nothing usable → caller skips
		} catch (RuntimeException e) {
			throw e;                                      // genuine resolution error → caller makes it visible
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Enforces the read represented by a dynamic context reference. Harness
	 * tools do not pass through an adapter, so this is their equivalent
	 * point-of-action gate.
	 */
	void requireReadAccess(AString ref, RequestContext ctx) {
		if (ref == null) return;
		String value = ref.toString();
		if (!isNamespacePath(value) && !isAssetReference(value)) return; // literal text
		AString ability = (AssetAdapter.parseAssetId(ref) != null)
			? Abilities.ASSET_READ : Capability.CRUD_READ;
		engine.requireResourceAccess(ctx, ref, ability);
	}

	/**
	 * Resolves a grid operation entry by invoking the operation.
	 */
	ACell resolveOpEntry(AString op, ACell input, AString label, boolean required, RequestContext ctx) {
		String labelStr = (label != null) ? label.toString() : "op:" + op;
		try {
			// Context loading is framework infrastructure: entries are
			// declared in the agent's config (visible to the caller before
			// invocation) and run pre-transition under the caller's identity.
			// invokeInternal is the framework dispatch path — no cap check
			// applied. Caps stay on ctx for audit.
			ACell result = engine.jobs().invokeInternal(op, input, ctx)
				.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
			if (result == null) {
				if (required) throw new RuntimeException("Required context operation returned null: " + op);
				return null;                              // produced nothing → skip
			}
			return systemMessage(labelStr, renderValue(result));
		} catch (RuntimeException e) {
			if (required) throw e;
			return errorMessage(labelStr, rootMessage(e));   // errored → visible
		} catch (Exception e) {
			if (required) throw new RuntimeException("Context operation failed: " + op + " — " + e.getMessage(), e);
			return errorMessage(labelStr, rootMessage(e));   // errored → visible
		}
	}

	/**
	 * Resolves a job result entry by reading the job output.
	 */
	ACell resolveJobEntry(AString jobId, AString path, AString label, boolean required, RequestContext ctx) {
		String labelStr = (label != null) ? label.toString() : "Job " + jobId;
		try {
			AMap<AString, ACell> jobData = engine.jobs().getJobData(
				convex.core.data.Blob.fromHex(jobId.toString()), ctx);
			if (jobData == null) {
				if (required) throw new RuntimeException("Required context job not found: " + jobId);
				return null;
			}

			ACell status = jobData.get(Fields.STATUS);
			if (!Strings.create("COMPLETE").equals(status)) {
				if (required) throw new RuntimeException("Required context job not complete: " + jobId + " status=" + status);
				return null;
			}

			ACell output = jobData.get(Fields.OUTPUT);
			if (path != null && output != null) {
				// Navigate into output by dot-separated path
				for (String key : path.toString().split("\\.")) {
					output = RT.getIn(output, Strings.create(key));
					if (output == null) break;
				}
			}

			if (output == null) {
				if (required) throw new RuntimeException("Required context job output is null: " + jobId);
				return null;
			}

			return systemMessage(labelStr, renderValue(output));
		} catch (RuntimeException e) {
			if (required) throw e;
			return errorMessage(labelStr, rootMessage(e));   // errored → visible
		}
	}

	/**
	 * Creates a system message map with optional label prefix.
	 */
	static ACell systemMessage(String label, String content) {
		String text = (label != null) ? "[Context: " + label + "]\n" + content : content;
		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, Strings.create(text));
	}

	/**
	 * Builds a visible "context source failed" system message. Used when an
	 * entry ERRORS while resolving (op throws/times out, a read genuinely
	 * fails) and is not {@code required} — so the LLM sees that this context
	 * source is broken (and can adapt / retry / tell the user) instead of
	 * silently operating without it. A merely absent/empty source is skipped,
	 * not reported here.
	 */
	static ACell errorMessage(String label, String reason) {
		String l = (label != null && !label.isEmpty()) ? label : "context";
		String r = (reason != null && !reason.isEmpty()) ? reason : "resolution failed";
		return Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT,
			Strings.create("[Context: " + l + " — unavailable: " + r + "]"));
	}

	/** Unwraps the most useful message from a (possibly wrapped) throwable. */
	static String rootMessage(Throwable e) {
		Throwable c = e;
		if (c instanceof java.util.concurrent.ExecutionException && c.getCause() != null) c = c.getCause();
		String m = c.getMessage();
		return (m != null && !m.isEmpty()) ? m : c.getClass().getSimpleName();
	}

	/**
	 * Derives a label from a reference string.
	 */
	public static String deriveLabel(String ref) {
		if (isNamespacePath(ref)) return ref;
		if (ref.length() == 64 && ref.matches("[0-9a-fA-F]+")) return ref.substring(0, 12) + "...";
		return ref;
	}
}

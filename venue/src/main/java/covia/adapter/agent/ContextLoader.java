package covia.adapter.agent;

import java.nio.charset.StandardCharsets;

import convex.auth.ucan.Capability;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Cells;
import convex.core.data.util.CellExplorer;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.AssetAdapter;
import covia.adapter.CoviaAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Resolves context entries into values for agent LLM context.
 *
 * <p>Context entries can be:</p>
 * <ul>
 *   <li>Literal text strings</li>
 *   <li>Workspace path references ({@code w/docs/rules})</li>
 *   <li>Content references (assets, file roots, DLFS paths, DID URLs)</li>
 *   <li>Job result references ({@code {"job": "0x..."}}</li>
 *   <li>Grid operation calls ({@code {"op": "v/ops/covia/read", "input": {...}}})</li>
 *   <li>Map entries with {@code ref}, {@code text}, {@code label}, {@code required} fields</li>
 * </ul>
 *
 * <p>See {@code venue/docs/AGENT_CONTEXT.md} for the full design.</p>
 */
public class ContextLoader {

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
	private long explorerBudget = Long.MAX_VALUE;
	private boolean truncated;
	private Resolution resolution = Resolution.RESOLVED;

	enum Resolution { RESOLVED, ABSENT, UNAVAILABLE }

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
		this.explorerBudget = Long.MAX_VALUE;
		this.truncated = false;
		this.resolution = Resolution.RESOLVED;
	}

	/** Starts one load's rendering trace without changing rendering semantics. */
	void beginTrace(long budget) {
		this.explorer = new CellExplorer((int) budget);
		this.explorerBudget = budget;
		this.truncated = false;
		this.resolution = Resolution.RESOLVED;
	}

	boolean wasTruncated() { return truncated; }
	Resolution resolution() { return resolution; }

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
		if (explorer != null) {
			if (Cells.storageSize(value) > explorerBudget) truncated = true;
			return explorer.explore(value).toString();
		}
		return value.toString();
	}

	/**
	 * A resolved entry as a value: its label, its rendered content — or the
	 * failure reason when {@code error} — and the provenance a renderer may
	 * show: the entry's source in its own terms ({@code ref}, {@code op} +
	 * {@code input}, {@code job} + {@code path}, or {@code source: text}).
	 */
	public record Resolved(String label, String content, boolean error, boolean absent,
			AMap<AString, ACell> provenance) {}

	private static final AString K_SOURCE = Strings.intern("source");
	private static final AString SOURCE_TEXT = Strings.intern("text");

	/**
	 * Resolves one entry to a value — null when absent. The declaration boundary
	 * decides whether a renderer admits it as instruction or shapes it as a tool
	 * result (AGENT_CONTEXT.md §5.3–5.5).
	 */
	public Resolved resolveValue(ACell entry, RequestContext ctx) {
		return resolveValue(entry, ctx, false);
	}

	/**
	 * Resolves one entry, optionally retaining a declared-but-absent source as a
	 * structured result. Initial pinned context uses this so an absent optional
	 * reference is visible rather than silently disappearing; ordinary dynamic
	 * loads retain the historical null/skip behaviour.
	 */
	public Resolved resolveValue(ACell entry, RequestContext ctx, boolean includeAbsent) {
		Resolved result = null;
		if (entry instanceof AString s) result = resolveStringEntry(s, ctx);
		if (entry instanceof AMap) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) entry;
			result = resolveMapEntry(map, ctx);
		}
		if (result != null || !includeAbsent || resolution != Resolution.ABSENT) return result;
		return absent(entry);
	}

	private Resolved resolved(String label, String content, AMap<AString, ACell> provenance) {
		resolution = Resolution.RESOLVED;
		return new Resolved(label, content, false, false, provenance);
	}

	private Resolved failed(String label, String reason, AMap<AString, ACell> provenance) {
		resolution = Resolution.UNAVAILABLE;
		return new Resolved(label, reason, true, false, provenance);
	}

	/** Metadata-only value for an optional declaration whose source is absent. */
	private Resolved absent(ACell entry) {
		String label = null;
		AMap<AString, ACell> provenance = Maps.empty();
		if (entry instanceof AString ref) {
			boolean reference = isNamespacePath(ref.toString()) || isAssetReference(ref.toString());
			label = reference ? deriveLabel(ref.toString()) : null;
			provenance = reference ? Maps.of(K_REF, ref) : Maps.of(K_SOURCE, SOURCE_TEXT);
		} else if (entry instanceof AMap<?, ?> raw) {
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> map = (AMap<AString, ACell>) raw;
			AString declaredLabel = RT.ensureString(map.get(K_LABEL));
			AString ref = RT.ensureString(map.get(K_REF));
			AString op = RT.ensureString(map.get(K_OP));
			AString job = RT.ensureString(map.get(K_JOB));
			AString text = RT.ensureString(map.get(K_TEXT));
			if (declaredLabel != null) label = declaredLabel.toString();
			if (ref != null) {
				if (label == null) label = deriveLabel(ref.toString());
				provenance = Maps.of(K_REF, ref);
			} else if (op != null) {
				if (label == null) label = "op:" + op;
				provenance = Maps.of(K_OP, op);
				ACell input = map.get(K_INPUT);
				if (input != null) provenance = provenance.assoc(K_INPUT, input);
			} else if (job != null) {
				if (label == null) label = "Job " + job;
				provenance = Maps.of(K_JOB, job);
				AString path = RT.ensureString(map.get(K_PATH));
				if (path != null) provenance = provenance.assoc(K_PATH, path);
			} else if (text != null) {
				provenance = Maps.of(K_SOURCE, SOURCE_TEXT);
			} else {
				return null;
			}
		} else {
			return null;
		}
		return new Resolved(label, null, false, true, provenance);
	}

	/**
	 * Resolves a string context entry. Interprets as workspace path, asset
	 * reference, or literal text based on prefix.
	 */
	Resolved resolveStringEntry(AString ref, RequestContext ctx) {
		String str = ref.toString();
		boolean reference = isNamespacePath(str) || isAssetReference(str);
		String label = reference ? deriveLabel(str) : null;
		AMap<AString, ACell> provenance = reference
			? Maps.of(K_REF, ref) : Maps.of(K_SOURCE, SOURCE_TEXT);
		try {
			String content = resolveReference(ref, ctx);
			if (content == null) {
				resolution = Resolution.ABSENT;
				return null;                                // absent/empty → skip
			}
			return resolved(label, content, provenance);
		} catch (RuntimeException e) {
			// String entries carry no `required` flag — surface the failure
			// visibly so the LLM knows this context source is broken.
			return failed(label, rootMessage(e), provenance);
		}
	}

	/**
	 * Resolves a map context entry with explicit fields.
	 */
	/**
	 * The raw text a map entry resolves to — the same forms as
	 * {@link #resolveMapEntry} ({@code ref}, {@code text}, {@code op} +
	 * {@code input}, {@code job} + {@code path}) with no header and
	 * <b>required</b> semantics: an absent source or a failure throws with
	 * the reason, and a value that is not text is an error rather than a
	 * rendering. This is what {@code config.systemPrompt} resolves through
	 * when it is an entry rather than a literal (AGENT_CONTEXT.md §5.1).
	 */
	public String resolveText(AMap<AString, ACell> entry, RequestContext ctx) {
		AString text = RT.ensureString(entry.get(K_TEXT));
		if (text != null) return text.toString();
		AString ref = RT.ensureString(entry.get(K_REF));
		if (ref != null) {
			String content = resolveReference(ref, ctx);
			if (content == null) throw new RuntimeException("not found: " + ref);
			return content;
		}
		AString op = RT.ensureString(entry.get(K_OP));
		if (op != null) {
			ACell result;
			try {
				result = engine.jobs().invokeInternal(op, entry.get(K_INPUT), ctx)
					.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				throw new RuntimeException("operation " + op + " failed: " + rootMessage(e), e);
			}
			AString s = RT.ensureString(result);
			if (s == null) {
				throw new RuntimeException("operation " + op + " returned "
					+ ((result == null) ? "nothing" : result.getClass().getSimpleName()) + ", not text");
			}
			return s.toString();
		}
		AString jobId = RT.ensureString(entry.get(K_JOB));
		if (jobId != null) {
			AMap<AString, ACell> jobData = engine.jobs().getJobData(
				convex.core.data.Blob.fromHex(jobId.toString()), ctx);
			if (jobData == null) throw new RuntimeException("job not found: " + jobId);
			ACell status = jobData.get(Fields.STATUS);
			if (!Strings.create("COMPLETE").equals(status)) {
				throw new RuntimeException("job " + jobId + " is not complete (" + status + ")");
			}
			ACell output = jobData.get(Fields.OUTPUT);
			AString path = RT.ensureString(entry.get(K_PATH));
			if (path != null && output != null) {
				for (String key : path.toString().split("\\.")) {
					output = RT.getIn(output, Strings.create(key));
					if (output == null) break;
				}
			}
			AString s = RT.ensureString(output);
			if (s == null) throw new RuntimeException("job " + jobId + " output is not text");
			return s.toString();
		}
		throw new IllegalArgumentException("entry declares none of ref, text, op, job");
	}

	Resolved resolveMapEntry(AMap<AString, ACell> map, RequestContext ctx) {
		AString label = RT.ensureString(map.get(K_LABEL));
		boolean required = convex.core.data.prim.CVMBool.TRUE.equals(map.get(K_REQUIRED));
		// Absent until a value says otherwise: resolved() / failed() record
		// RESOLVED / UNAVAILABLE, so a null return here reads as absent.
		resolution = Resolution.ABSENT;

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
			return resolved(labelStr, text.toString(), Maps.of(K_SOURCE, SOURCE_TEXT));
		}

		// Reference entry
		AString ref = RT.ensureString(map.get(K_REF));
		if (ref != null) {
			String labelStr = (label != null) ? label.toString() : deriveLabel(ref.toString());
			AMap<AString, ACell> provenance = Maps.of(K_REF, ref);
			try {
				String content = resolveReference(ref, ctx);
				if (content == null) {
					if (required) throw new RuntimeException("Required context entry not found: " + ref);
					return null;                          // absent/empty → skip
				}
				return resolved(labelStr, content, provenance);
			} catch (RuntimeException e) {
				if (required) throw e;
				return failed(labelStr, rootMessage(e), provenance);   // errored → visible
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

		// Try as a content/asset reference (hash, lattice path, file, DLFS, DID URL)
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
	 * <p>References include hex hashes, /a/ and /o/ paths, DID URLs, file/DLFS
	 * content references, lattice
	 * namespace paths (w/, g/, j/, s/, h/, n/, t/, o/), and venue catalog
	 * paths under /v/. Strings with spaces are treated as literal text.</p>
	 */
	public static boolean isAssetReference(String ref) {
		if (ref == null || ref.isEmpty() || ref.contains(" ")) return false;
		if (ref.startsWith("/a/") || ref.startsWith("/o/")) return true;
		if (ref.startsWith("did:")) return true;
		if (ref.startsWith("file:/") || ref.startsWith("dlfs/") || ref.startsWith("dlfs://")) return true;
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
			// 1. Content, via the universal resolution chain (UTF-8 decode). The
			// resolver owns the point-of-action check: provider refs use crud/read,
			// while asset/lattice content uses asset/read.
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
	Resolved resolveOpEntry(AString op, ACell input, AString label, boolean required, RequestContext ctx) {
		String labelStr = (label != null) ? label.toString() : "op:" + op;
		AMap<AString, ACell> provenance = Maps.of(K_OP, op);
		if (input != null) provenance = provenance.assoc(K_INPUT, input);
		try {
			Asset asset = engine.resolveAsset(op, ctx);
			if (asset == null) throw new IllegalArgumentException(
				"Cannot resolve context operation: " + op);
			if (!convex.core.data.prim.CVMBool.TRUE.equals(
					RT.getIn(asset.meta(), Fields.OPERATION, Fields.READ_ONLY))) {
				throw new IllegalArgumentException("Context operation must declare operation.readOnly=true: " + op);
			}
			// Context loading is framework infrastructure: entries are
			// declared in the agent's config (visible to the caller before
			// invocation) and run pre-transition under the caller's identity.
			// invokeInternal is the framework dispatch path — no cap check
			// applied. Caps stay on ctx for audit.
			ACell result = engine.jobs().invokeInternal(asset.meta(), input, ctx.withOp(op))
				.get(10_000, java.util.concurrent.TimeUnit.MILLISECONDS);
			if (result == null) {
				if (required) throw new RuntimeException("Required context operation returned null: " + op);
				return null;                              // produced nothing → skip
			}
			return resolved(labelStr, renderValue(result), provenance);
		} catch (RuntimeException e) {
			if (required) throw e;
			return failed(labelStr, rootMessage(e), provenance);   // errored → visible
		} catch (Exception e) {
			if (required) throw new RuntimeException("Context operation failed: " + op + " — " + e.getMessage(), e);
			return failed(labelStr, rootMessage(e), provenance);   // errored → visible
		}
	}

	/**
	 * Resolves a job result entry by reading the job output.
	 */
	Resolved resolveJobEntry(AString jobId, AString path, AString label, boolean required, RequestContext ctx) {
		String labelStr = (label != null) ? label.toString() : "Job " + jobId;
		AMap<AString, ACell> provenance = Maps.of(K_JOB, jobId);
		if (path != null) provenance = provenance.assoc(K_PATH, path);
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

			return resolved(labelStr, renderValue(output), provenance);
		} catch (RuntimeException e) {
			if (required) throw e;
			return failed(labelStr, rootMessage(e), provenance);   // errored → visible
		}
	}

	/** Unwraps the most useful message from a (possibly wrapped) throwable. */
	public static String rootMessage(Throwable e) {
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

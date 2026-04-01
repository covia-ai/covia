package covia.adapter;

import java.nio.charset.StandardCharsets;

import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.api.Fields;
import convex.lattice.cursor.ALatticeCursor;
import covia.grid.Asset;
import covia.grid.Job;
import covia.venue.AssetStore;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.User;
import covia.venue.Users;

/**
 * Resolves context entries into system messages for agent LLM context.
 *
 * <p>Context entries can be:</p>
 * <ul>
 *   <li>Literal text strings</li>
 *   <li>Workspace path references ({@code w/docs/rules})</li>
 *   <li>Asset references (hex hash, {@code /a/}, {@code /o/}, DID URL, registered name)</li>
 *   <li>Job result references ({@code {"job": "0x..."}}</li>
 *   <li>Grid operation calls ({@code {"op": "covia:read", "input": {...}}})</li>
 *   <li>Map entries with {@code ref}, {@code text}, {@code label}, {@code required} fields</li>
 * </ul>
 *
 * <p>See {@code venue/docs/CONTEXT.md} for the full design.</p>
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

	public ContextLoader(Engine engine) {
		this.engine = engine;
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
	ACell resolveEntry(ACell entry, RequestContext ctx) {
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
		String content = resolveReference(ref, ctx);
		if (content == null) return null;

		String label = deriveLabel(ref.toString());
		return systemMessage(label, content);
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
			String content = resolveReference(ref, ctx);
			if (content == null) {
				if (required) throw new RuntimeException("Required context entry not found: " + ref);
				return null;
			}
			String labelStr = (label != null) ? label.toString() : deriveLabel(ref.toString());
			return systemMessage(labelStr, content);
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
	static boolean isNamespacePath(String ref) {
		return ref.startsWith("w/") || ref.startsWith("g/") || ref.startsWith("o/")
			|| ref.startsWith("j/") || ref.startsWith("s/") || ref.startsWith("h/");
	}

	/**
	 * Returns true if the string looks like an asset reference rather than literal text.
	 */
	static boolean isAssetReference(String ref) {
		if (ref.startsWith("/a/") || ref.startsWith("/o/")) return true;
		if (ref.startsWith("did:")) return true;
		if (ref.contains(":") && !ref.contains(" ")) return true; // adapter:op pattern
		if (ref.length() == 64 && ref.matches("[0-9a-fA-F]+")) return true; // hex hash
		return false;
	}

	/**
	 * Reads a value from the user's lattice namespace using the internal
	 * cursor-based path resolution (same as CoviaAdapter.readPath).
	 */
	String resolveWorkspacePath(String path, RequestContext ctx) {
		try {
			Users users = engine.getVenueState().users();
			User user = users.get(ctx.getCallerDID());
			if (user == null) return null;

			ALatticeCursor<ACell> cursor = user.cursor();
			ACell[] pathKeys = CoviaAdapter.parseStringPath(path);
			if (pathKeys.length == 0) return null;

			ACell value = CoviaAdapter.readPath(cursor, pathKeys);
			if (value == null) return null;
			return value.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolves an asset reference and extracts text content.
	 * Tries: artifact content (UTF-8) → metadata description → null.
	 */
	String resolveAssetContent(AString ref, RequestContext ctx) {
		// If it doesn't look like a reference, treat as literal text
		if (!isAssetReference(ref.toString())) return ref.toString();

		try {
			Asset asset = engine.resolveAsset(ref, ctx);
			if (asset == null) return null;

			// Try text content payload first
			Hash assetHash = asset.getID();
			AVector<?> record = engine.getVenueState().assets().getRecord(assetHash);
			if (record != null) {
				ACell content = record.get(AssetStore.POS_CONTENT);
				if (content instanceof ABlob blob && blob.count() > 0) {
					return new String(blob.getBytes(), StandardCharsets.UTF_8);
				}
			}

			// Fall back to description
			AString desc = RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
			if (desc != null) return desc.toString();

			return null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Resolves a grid operation entry by invoking the operation.
	 */
	ACell resolveOpEntry(AString op, ACell input, AString label, boolean required, RequestContext ctx) {
		try {
			Job job = engine.jobs().invokeOperation(op, input, ctx);
			ACell result = job.awaitResult(10_000);
			if (result == null) {
				if (required) throw new RuntimeException("Required context operation returned null: " + op);
				return null;
			}
			String labelStr = (label != null) ? label.toString() : "op:" + op;
			return systemMessage(labelStr, result.toString());
		} catch (RuntimeException e) {
			if (required) throw e;
			return null;
		}
	}

	/**
	 * Resolves a job result entry by reading the job output.
	 */
	ACell resolveJobEntry(AString jobId, AString path, AString label, boolean required, RequestContext ctx) {
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

			String labelStr = (label != null) ? label.toString() : "Job " + jobId;
			return systemMessage(labelStr, output.toString());
		} catch (RuntimeException e) {
			if (required) throw e;
			return null;
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
	 * Derives a label from a reference string.
	 */
	static String deriveLabel(String ref) {
		if (isNamespacePath(ref)) return ref;
		if (ref.length() == 64 && ref.matches("[0-9a-fA-F]+")) return ref.substring(0, 12) + "...";
		return ref;
	}
}

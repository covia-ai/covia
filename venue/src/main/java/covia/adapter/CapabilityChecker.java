package covia.adapter;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.lang.RT;

/**
 * Checks agent tool calls against declared capability attenuations.
 *
 * <p>If an agent has a {@code caps} array in its config, every tool call
 * is checked before dispatch. The caps format matches UCAN attenuations:
 * {@code [{with: "w/decisions", can: "crud/write"}, ...]}</p>
 *
 * <p>No caps = full access (no restriction). This is the default.</p>
 *
 * <p>Matching uses {@link Capability#covers} from convex-core — the same
 * logic used for UCAN proof verification.</p>
 *
 * <p>See {@code venue/docs/UCAN.md §5.4} for the full design.</p>
 */
public class CapabilityChecker {

	private static final AString K_PATH = Strings.intern("path");

	/**
	 * Checks whether an operation invocation is allowed by the agent's caps.
	 *
	 * @param caps Capability attenuations (null = unrestricted)
	 * @param operation The operation being invoked (e.g. "v/ops/covia/write")
	 * @param input The tool call input
	 * @return null if allowed, or an error message string if denied
	 */
	public static String check(AVector<ACell> caps, String operation, ACell input) {
		if (caps == null) return null; // no caps = full access

		String ability = operationAbility(operation);
		String resource = extractResource(operation, input);
		if (resource == null) resource = "";

		for (long i = 0; i < caps.count(); i++) {
			// Skip non-map entries — defensive against malformed caps data
			if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;

			if (Capability.covers(cap, resource, ability)) {
				return null; // allowed
			}
		}

		// Build an actionable denial message that includes WHAT the agent
		// can do, not just what it can't. Without this LLMs that hit a
		// denial often retry the same impossible call because they have no
		// idea what the actual boundaries are.
		StringBuilder sb = new StringBuilder("Capability denied: ")
			.append(operation).append(" requires ").append(ability)
			.append(" on ").append(resource.isEmpty() ? "(any)" : resource)
			.append(". Your capabilities are: ");
		appendCapsList(sb, caps);
		sb.append(". Retrying the same call will not succeed — the denial is "
			+ "structural. If your goal cannot be met within these "
			+ "capabilities, complete with a clear explanation rather than "
			+ "looping on impossible operations.");
		return sb.toString();
	}

	/**
	 * Appends a compact "ability on resource, ability on resource, …" list
	 * for inclusion in error messages. Empty caps render as {@code (none)}.
	 */
	private static void appendCapsList(StringBuilder sb, AVector<ACell> caps) {
		if (caps == null || caps.count() == 0) {
			sb.append("(none)");
			return;
		}
		boolean first = true;
		for (long i = 0; i < caps.count(); i++) {
			if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
			@SuppressWarnings("unchecked")
			AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;
			AString with = RT.ensureString(cap.get(Strings.intern("with")));
			AString can  = RT.ensureString(cap.get(Strings.intern("can")));
			if (!first) sb.append(", ");
			first = false;
			sb.append(can != null ? can.toString() : "(any)")
			  .append(" on ")
			  .append(with != null ? with.toString() : "(any)");
		}
	}

	/**
	 * Maps an operation name to a required ability.
	 */
	/**
	 * Maps an operation reference to a required ability. Accepts both the
	 * legacy dispatch-string form ({@code "covia:write"}) — which is what
	 * {@link covia.venue.JobManager} sees in {@code operation.adapter}
	 * during invocation — and the catalog path form ({@code "v/ops/covia/write"}).
	 */
	static String operationAbility(String operation) {
		return switch (operation) {
			// Catalog path form
			case "v/ops/covia/read", "v/ops/covia/list", "v/ops/covia/slice" -> "crud/read";
			case "v/ops/covia/write", "v/ops/covia/append" -> "crud/write";
			case "v/ops/covia/delete" -> "crud/delete";
			case "v/ops/agent/create" -> "agent/create";
			case "v/ops/agent/request" -> "agent/request";
			case "v/ops/agent/message" -> "agent/message";
			case "v/ops/asset/store" -> "asset/store";
			case "v/ops/asset/get", "v/ops/asset/list" -> "asset/read";
			case "v/ops/grid/run" -> "invoke";
			// Legacy dispatch-string form (operation.adapter)
			case "covia:read", "covia:list", "covia:slice" -> "crud/read";
			case "covia:write", "covia:append" -> "crud/write";
			case "covia:delete" -> "crud/delete";
			case "agent:create" -> "agent/create";
			case "agent:request" -> "agent/request";
			case "agent:message" -> "agent/message";
			case "asset:store" -> "asset/store";
			case "asset:get", "asset:list" -> "asset/read";
			case "grid:run" -> "invoke";
			default -> "invoke";
		};
	}

	/**
	 * Extracts the resource path from a tool call input, if applicable.
	 * Returns null for operations that don't target a specific path.
	 */
	static String extractResource(String operation, ACell input) {
		// Path-targeted ops: pull the path arg out of the input.
		if (operation.startsWith("v/ops/covia/") || operation.startsWith("covia:")) {
			AString path = RT.ensureString(RT.getIn(input, K_PATH));
			return (path != null) ? path.toString() : null;
		}
		// Agent-targeted ops: derive a g/<id> resource string from the agentId.
		if ("v/ops/agent/request".equals(operation) || "v/ops/agent/message".equals(operation)
				|| "v/ops/agent/create".equals(operation)
				|| "agent:request".equals(operation) || "agent:message".equals(operation)
				|| "agent:create".equals(operation)) {
			AString agentId = RT.ensureString(RT.getIn(input, Strings.intern("agentId")));
			return (agentId != null) ? "g/" + agentId : "g/";
		}
		return null;
	}
}

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
	 * @param operation The operation being invoked (e.g. "covia:write")
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

		return "Capability denied: " + operation + " requires " + ability
			+ " on " + (resource.isEmpty() ? "(any)" : resource)
			+ " — not covered by agent caps";
	}

	/**
	 * Maps an operation name to a required ability.
	 */
	static String operationAbility(String operation) {
		return switch (operation) {
			case "covia:read", "covia:list", "covia:slice" -> "crud/read";
			case "covia:write", "covia:append" -> "crud/write";
			case "covia:delete" -> "crud/delete";
			case "agent:create" -> "agent/create";
			case "agent:request" -> "agent/request";
			case "agent:message" -> "agent/message";
			case "agent:query" -> "agent/query";
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
		if (operation.startsWith("covia:")) {
			AString path = RT.ensureString(RT.getIn(input, K_PATH));
			return (path != null) ? path.toString() : null;
		}
		if ("agent:request".equals(operation) || "agent:message".equals(operation)
				|| "agent:query".equals(operation) || "agent:create".equals(operation)) {
			AString agentId = RT.ensureString(RT.getIn(input, Strings.intern("agentId")));
			return (agentId != null) ? "g/" + agentId : "g/";
		}
		return null;
	}
}

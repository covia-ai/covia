package covia.adapter.messaging;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.exception.InvalidInputException;
import covia.lattice.CapabilityChecker;
import covia.utils.NearestNames;
import covia.venue.AgentAddress;

/** Small validation helpers shared by HTTP messaging providers. */
public final class MessagingSettings {
	public static final String OBJECT_REQUIRED = "Settings/input must be an object";
	public static final String REQUIRED = "%s must be a non-empty string";
	public static final String BOOLEAN = "%s must be boolean";
	public static final String ARRAY = "%s must be an array of non-empty string IDs";
	/** A setting or input field not known here: its name, then {@link NearestNames#hint} or "". */
	public static final String UNKNOWN = "Unknown setting: %s%s";
	public static final String INVALID_NAME = "Bot name must match [A-Za-z0-9_-]+";
	public static final String INVALID_USER = "user must be a bare DID, u:<user> for a user of this venue, or public";
	public static final String HANDLER = "Exactly one of agent, operation or relay is required";
	public static final String REPLY = "reply must be boolean or string and only applies to operation handlers";
	public static final String CREDENTIAL = "%s must be an s/NAME secret reference";
	public static final String INVALID_ID = "%s has an invalid ID format";
	/** Refusal for an agent setting that names no agent: the setting, then why. */
	public static final String INVALID_AGENT = "%s must name one agent: %s";
	/** Settings every webhook provider's binding accepts. */
	public static final Set<String> COMMON = Set.of("user", "agent", "operation", "relay", "reply", "allow", "open", "token");
	private MessagingSettings() { }
	public static AString key(String name) { return Strings.intern(name); }
	public static AMap<AString, ACell> object(ACell value) {
		if (!(value instanceof AMap<?, ?>)) throw new IllegalArgumentException(OBJECT_REQUIRED);
		return RT.castMap(value);
	}
	public static String optional(AMap<AString, ACell> map, String field) {
		ACell value = map.get(key(field));
		if (value == null) return null;
		if (!(value instanceof AString s) || s.isBlank()) throw new IllegalArgumentException(REQUIRED.formatted(field));
		return s.toString();
	}
	public static String required(AMap<AString, ACell> map, String field) {
		String value = optional(map, field);
		if (value == null) throw new IllegalArgumentException(REQUIRED.formatted(field));
		return value;
	}
	public static boolean bool(AMap<AString, ACell> map, String field, boolean fallback) {
		ACell value = map.get(key(field));
		if (value == null) return fallback;
		if (!(value instanceof CVMBool b)) throw new IllegalArgumentException(BOOLEAN.formatted(field));
		return b.booleanValue();
	}
	public static Set<String> ids(AMap<AString, ACell> map, String field, String pattern) {
		ACell value = map.get(key(field));
		if (value == null) return Set.of();
		if (!(value instanceof AVector<?> values)) throw new IllegalArgumentException(ARRAY.formatted(field));
		Set<String> result = new LinkedHashSet<>();
		for (ACell item : values) {
			if (!(item instanceof AString s) || !s.toString().matches(pattern)) throw new IllegalArgumentException(ARRAY.formatted(field));
			result.add(s.toString());
		}
		return Set.copyOf(result);
	}
	public static String id(AMap<AString, ACell> map, String field, String pattern) {
		String value = required(map, field);
		if (!value.matches(pattern)) throw new IllegalArgumentException(INVALID_ID.formatted(field));
		return value;
	}
	/**
	 * Checks that an agent setting names one agent ({@link AgentAddress#parse}),
	 * reading a relative reference as an agent of the binding's {@code user}.
	 *
	 * @param setting The setting, as its refusal names it
	 * @param agent The setting's value, or null when there is none
	 * @throws IllegalArgumentException when it names no agent
	 */
	public static void agent(String setting, String user, String agent) {
		if (agent == null) return;
		try {
			AgentAddress.parse(Strings.create(user), Strings.create(agent));
		} catch (InvalidInputException e) {
			throw new IllegalArgumentException(INVALID_AGENT.formatted(setting, e.getMessage()), e);
		}
	}
	public static void known(AMap<AString, ACell> map, Set<String> fields) {
		for (var entry : map.entrySet()) if (!(entry.getKey() instanceof AString s) || !fields.contains(s.toString())) {
			// Sorted, so the hint never depends on the set's order
			String given = String.valueOf(entry.getKey());
			throw new IllegalArgumentException(UNKNOWN.formatted(given, NearestNames.hint(given, new TreeSet<>(fields))));
		}
	}
	public static void validate(String name, AMap<AString, ACell> map, Set<String> fields, Set<String> credentials, boolean strict) {
		if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException(INVALID_NAME);
		if (strict) known(map, fields);
		if (map.containsKey(key("statePath"))) throw new IllegalArgumentException(UNKNOWN.formatted("statePath", ""));
		String user = required(map, "user");
		if (!user.equals("public") && (!CapabilityChecker.namesPrincipal(user) || user.contains("/") || user.isBlank())) throw new IllegalArgumentException(INVALID_USER);
		String agent = optional(map, "agent"), operation = optional(map, "operation");
		ACell relay = map.get(key("relay"));
		if ((agent != null ? 1 : 0) + (operation != null ? 1 : 0) + (relay != null ? 1 : 0) != 1) throw new IllegalArgumentException(HANDLER);
		agent("agent", user, agent);
		if (relay != null) {
			RelaySettings.parse(relay, strict);
			if (user.equals("public")) throw new IllegalArgumentException(RelaySettings.PUBLIC_USER);
		}
		ACell reply = map.get(key("reply"));
		if (reply != null && (operation == null || !(reply instanceof AString || reply instanceof CVMBool))) throw new IllegalArgumentException(REPLY);
		bool(map, "open", false);
		for (String field : credentials) {
			if (!required(map, field).matches("/?s/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException(CREDENTIAL.formatted(field));
		}
	}
}

package covia.adapter.messaging;

import java.util.LinkedHashSet;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;

/** Small validation helpers shared by HTTP messaging providers. */
public final class MessagingSettings {
	public static final String OBJECT_REQUIRED = "Settings/input must be an object";
	public static final String REQUIRED = "%s must be a non-empty string";
	public static final String BOOLEAN = "%s must be boolean";
	public static final String ARRAY = "%s must be an array of non-empty string IDs";
	public static final String UNKNOWN = "Unknown setting: %s";
	public static final String INVALID_NAME = "Bot name must match [A-Za-z0-9_-]+";
	public static final String INVALID_USER = "user must be a bare DID or public";
	public static final String HANDLER = "Exactly one of agent or operation is required";
	public static final String REPLY = "reply must be boolean or string and only applies to operation handlers";
	public static final String CREDENTIAL = "%s must be an s/NAME secret reference";
	public static final String INVALID_ID = "%s has an invalid ID format";
	public static final Set<String> COMMON = Set.of("user", "agent", "operation", "reply", "allow", "open", "token");
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
	public static void known(AMap<AString, ACell> map, Set<String> fields) {
		for (var entry : map.entrySet()) if (!(entry.getKey() instanceof AString s) || !fields.contains(s.toString())) {
			throw new IllegalArgumentException(UNKNOWN.formatted(entry.getKey()));
		}
	}
	public static void validate(String name, AMap<AString, ACell> map, Set<String> fields, Set<String> credentials, boolean strict) {
		if (!name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException(INVALID_NAME);
		if (strict) known(map, fields);
		if (map.containsKey(key("statePath"))) throw new IllegalArgumentException(UNKNOWN.formatted("statePath"));
		String user = required(map, "user");
		if (!user.equals("public") && (!user.startsWith("did:") || user.contains("/") || user.isBlank())) throw new IllegalArgumentException(INVALID_USER);
		String agent = optional(map, "agent"), operation = optional(map, "operation");
		if ((agent == null) == (operation == null)) throw new IllegalArgumentException(HANDLER);
		ACell reply = map.get(key("reply"));
		if (reply != null && (agent != null || !(reply instanceof AString || reply instanceof CVMBool))) throw new IllegalArgumentException(REPLY);
		bool(map, "open", false);
		for (String field : credentials) {
			if (!required(map, field).matches("/?s/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException(CREDENTIAL.formatted(field));
		}
	}
}

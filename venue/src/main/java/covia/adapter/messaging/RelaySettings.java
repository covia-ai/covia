package covia.adapter.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * A binding's {@code relay} handler: {@code true} for the defaults, or an object of
 * {@code holdSeconds}, {@code maxParts}, {@code admit}, {@code charge} and
 * {@code notices}. Parsed whole when the binding is, so a binding with a malformed
 * relay is refused before it starts.
 */
public record RelaySettings(int holdSeconds, int maxParts, String admit, String charge, Map<String, String> notices) {

	public static final String INVALID = "relay must be true or an object of relay settings";
	public static final String HOLD = "relay.holdSeconds must be an integer from 0 to 86400";
	public static final String MAX_PARTS = "relay.maxParts must be an integer from 1 to 100";
	public static final String NOTICES = "relay.notices must map known notice names to non-empty strings";
	public static final String PUBLIC_USER = "A relay binding needs an owner DID: its hooks run as that user";
	static final Set<String> FIELDS = Set.of("holdSeconds", "maxParts", "admit", "charge", "notices");

	/** The hub's own replies to a sender, with their defaults. Operators override any. */
	public static final Map<String, String> DEFAULT_NOTICES = Map.of(
		RelayBinding.N_LINKED, "Linked. Your messages here now reach your service while it is running. Send STOP to unlink.",
		RelayBinding.N_UNLINKED, "Unlinked. Your messages here no longer reach the service.",
		RelayBinding.N_NOT_LINKED, "You are not linked. Ask the service you want to reach for a linking code, then send it here as LINK followed by the code.",
		RelayBinding.N_CODE_UNKNOWN, "That linking code is not recognised or has expired. Ask the service for a new one.",
		RelayBinding.N_NOT_OPEN, "Your service is not running, so this message cannot be answered. Send it again once it is.",
		RelayBinding.N_HELD, "Your service is not running. It will answer when it is.",
		RelayBinding.N_TEXT_ONLY, "This kind of message cannot be relayed. Send text, a voice note, a photo or a file.",
		RelayBinding.N_NOT_AVAILABLE, "This service cannot answer you at the moment.");

	public RelaySettings {
		notices = Map.copyOf(notices);
	}

	/** Parse a binding's {@code relay} value; strict refuses unknown fields. */
	public static RelaySettings parse(ACell value, boolean strict) {
		if (CVMBool.TRUE.equals(value)) return new RelaySettings(60, 10, null, null, DEFAULT_NOTICES);
		if (!(value instanceof AMap<?, ?>)) throw new IllegalArgumentException(INVALID);
		AMap<AString, ACell> map = RT.castMap(value);
		if (strict) MessagingSettings.known(map, FIELDS);
		int hold = bounded(map.get(MessagingSettings.key("holdSeconds")), 60, 0, 86_400, HOLD);
		int maxParts = bounded(map.get(MessagingSettings.key("maxParts")), 10, 1, 100, MAX_PARTS);
		Map<String, String> notices = new HashMap<>(DEFAULT_NOTICES);
		ACell givenCell = map.get(MessagingSettings.key("notices"));
		if (givenCell != null) {
			if (!(givenCell instanceof AMap<?, ?>)) throw new IllegalArgumentException(NOTICES);
			AMap<AString, ACell> given = RT.castMap(givenCell);
			for (var e : given.entrySet()) {
				String key = e.getKey().toString();
				if (!DEFAULT_NOTICES.containsKey(key) || !(e.getValue() instanceof AString s) || s.isBlank()) {
					throw new IllegalArgumentException(NOTICES);
				}
				notices.put(key, s.toString());
			}
		}
		return new RelaySettings(hold, maxParts, MessagingSettings.optional(map, "admit"),
			MessagingSettings.optional(map, "charge"), notices);
	}

	private static int bounded(ACell cell, int fallback, int min, int max, String message) {
		if (cell == null) return fallback;
		CVMLong n = RT.ensureLong(cell);
		if (n == null || n.longValue() < min || n.longValue() > max) throw new IllegalArgumentException(message);
		return (int) n.longValue();
	}

	/** The text of a named notice. */
	public String notice(String name) {
		return notices.get(name);
	}
}

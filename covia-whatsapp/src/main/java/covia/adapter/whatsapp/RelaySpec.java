package covia.adapter.whatsapp;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.messaging.MessagingSettings;

/** One relayed WhatsApp Cloud API number, as operator configuration declares it. */
record RelaySpec(String name, AString user, String token, String appSecret, String verifyToken,
		String businessAccountId, String phoneNumberId, String apiVersion, String number,
		int holdSeconds, int maxParts, String admit, String charge, Map<String, String> notices) {

	static final Set<String> FIELDS = Set.of("user", "token", "appSecret", "verifyToken", "businessAccountId",
		"phoneNumberId", "apiVersion", "number", "holdSeconds", "maxParts", "admit", "charge", "notices");
	static final Set<String> CREDENTIALS = Set.of("token", "appSecret", "verifyToken");
	static final String HOLD = "holdSeconds must be an integer from 0 to 86400";
	static final String MAX_PARTS = "maxParts must be an integer from 1 to 100";
	static final String NOTICES = "notices must map known notice names to non-empty strings";
	static final String INVALID_NAME = "Relay name must match [A-Za-z0-9_-]+";

	/** The server's own replies to a phone, with their defaults. Operators override any. */
	static final Map<String, String> DEFAULT_NOTICES = Map.of(
		"linked", "Linked. Your messages here now reach your service while it is running. Send STOP to unlink.",
		"unlinked", "Unlinked. This number no longer reaches the service.",
		"notLinked", "This number is not linked. Ask the service you want to reach for a linking code, then send it here as LINK followed by the code.",
		"codeUnknown", "That linking code is not recognised or has expired. Ask the service for a new one.",
		"notOpen", "Your service is not running, so this message cannot be answered. Send it again once it is.",
		"held", "Your service is not running. It will answer when it is.",
		"textOnly", "Only text messages can be relayed.",
		"notAvailable", "This service cannot answer for this number at the moment.");

	static RelaySpec parse(String name, ACell value, boolean strict) {
		if (name == null || !name.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException(INVALID_NAME);
		AMap<AString, ACell> map = MessagingSettings.object(value);
		if (strict) MessagingSettings.known(map, FIELDS);
		String user = MessagingSettings.required(map, "user");
		if (!user.startsWith("did:") || user.contains("/")) throw new IllegalArgumentException(MessagingSettings.INVALID_USER);
		for (String field : CREDENTIALS) {
			if (!MessagingSettings.required(map, field).matches("/?s/[A-Za-z0-9_.-]+")) {
				throw new IllegalArgumentException(MessagingSettings.CREDENTIAL.formatted(field));
			}
		}
		int hold = 60;
		ACell holdCell = map.get(MessagingSettings.key("holdSeconds"));
		if (holdCell != null) {
			CVMLong h = RT.ensureLong(holdCell);
			if (h == null || h.longValue() < 0 || h.longValue() > 86_400) throw new IllegalArgumentException(HOLD);
			hold = (int) h.longValue();
		}
		int maxParts = 10;
		ACell partsCell = map.get(MessagingSettings.key("maxParts"));
		if (partsCell != null) {
			CVMLong p = RT.ensureLong(partsCell);
			if (p == null || p.longValue() < 1 || p.longValue() > 100) throw new IllegalArgumentException(MAX_PARTS);
			maxParts = (int) p.longValue();
		}
		Map<String, String> notices = new HashMap<>(DEFAULT_NOTICES);
		ACell noticeCell = map.get(MessagingSettings.key("notices"));
		if (noticeCell != null) {
			AMap<AString, ACell> given = MessagingSettings.object(noticeCell);
			for (var e : given.entrySet()) {
				String key = e.getKey().toString();
				if (!DEFAULT_NOTICES.containsKey(key) || !(e.getValue() instanceof AString s) || s.isBlank()) {
					throw new IllegalArgumentException(NOTICES);
				}
				notices.put(key, s.toString());
			}
		}
		return new RelaySpec(name, Strings.create(user),
			MessagingSettings.required(map, "token"),
			MessagingSettings.required(map, "appSecret"),
			MessagingSettings.required(map, "verifyToken"),
			MessagingSettings.id(map, "businessAccountId", "[0-9]+"),
			MessagingSettings.id(map, "phoneNumberId", "[0-9]+"),
			MessagingSettings.id(map, "apiVersion", "v[0-9]+\\.[0-9]+"),
			MessagingSettings.id(map, "number", "[0-9]{5,20}"),
			hold,
			maxParts,
			MessagingSettings.optional(map, "admit"),
			MessagingSettings.optional(map, "charge"),
			Map.copyOf(notices));
	}

	String notice(String key) {
		return notices.get(key);
	}
}

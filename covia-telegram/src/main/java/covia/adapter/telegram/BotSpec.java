package covia.adapter.telegram;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Engine;

/**
 * One operator-declared bot: the immutable, validated form of an
 * {@code adapters.telegram.bots.<name>} entry.
 *
 * <p>A spec is pure configuration — it never holds the resolved token, a
 * client, or any runtime state. {@link BotRunner} owns those. Two specs that
 * are {@link #equals equal} describe the same bot, which is how the adapter
 * decides whether a reconfiguration must restart a running bot.</p>
 *
 * @param name       Bot name (the config key); {@code [A-Za-z0-9_-]+}
 * @param tokenRef   Bot token: an {@code s/NAME} secret reference or a literal
 * @param userRef    DID the bot acts as (its owner's namespace), or
 *                   {@code "public"} for the venue's public principal
 * @param agent      Agent id routed to via {@code agent:chat}, or null
 * @param operation  Operation reference invoked per message, or null
 * @param reply      For an operation handler: null = default (the result
 *                   rendered as text), {@code CVMBool.FALSE} = never reply, an
 *                   {@code AString} = that fixed acknowledgement
 * @param allowIds   Telegram user ids permitted to talk to the bot
 * @param allowNames Telegram usernames (lower-case, no {@code @}) permitted
 * @param open       Whether anyone may talk to the bot
 * @param parseMode  Outbound formatting: {@code Markdown}, {@code MarkdownV2},
 *                   {@code HTML}, or null for plain text
 * @param greeting   Reply to {@code /start}, or null for the default
 */
public record BotSpec(
		String name,
		String tokenRef,
		String userRef,
		String agent,
		String operation,
		ACell reply,
		Set<Long> allowIds,
		Set<String> allowNames,
		boolean open,
		String parseMode,
		String greeting) {

	static final AString K_TOKEN = Strings.intern("token");
	static final AString K_USER = Strings.intern("user");
	static final AString K_AGENT = Strings.intern("agent");
	static final AString K_OPERATION = Strings.intern("operation");
	static final AString K_REPLY = Strings.intern("reply");
	static final AString K_ALLOW = Strings.intern("allow");
	static final AString K_OPEN = Strings.intern("open");
	static final AString K_PARSE_MODE = Strings.intern("parseMode");
	static final AString K_GREETING = Strings.intern("greeting");

	private static final Set<AString> KNOWN_KEYS = Set.of(
		K_TOKEN, K_USER, K_AGENT, K_OPERATION, K_REPLY, K_ALLOW, K_OPEN, K_PARSE_MODE, K_GREETING);

	private static final Set<String> PARSE_MODES = Set.of("Markdown", "MarkdownV2", "HTML");

	/** The {@code user} value naming the venue's public principal. */
	static final String PUBLIC_USER = "public";

	public BotSpec {
		allowIds = Collections.unmodifiableSet(new LinkedHashSet<>(allowIds));
		allowNames = Collections.unmodifiableSet(new LinkedHashSet<>(allowNames));
	}

	/**
	 * The DID the bot acts as. {@code "public"} resolves against the venue at
	 * call time, because the public principal's DID is only known once the
	 * adapter is installed.
	 *
	 * @throws IllegalStateException when the spec names the public principal
	 *         but the venue has public access disabled
	 */
	public AString userDID(Engine engine) {
		if (!PUBLIC_USER.equals(userRef)) return Strings.create(userRef);
		if (engine == null || !engine.config().isPublicAccess()) {
			throw new IllegalStateException("bot '" + name + "' acts as \"public\" but public access "
				+ "is disabled on this venue (auth.public.enabled) — name an explicit DID");
		}
		return Strings.create(engine.getDIDString() + ":public");
	}

	/** Whether inbound messages go to an agent conversation. */
	public boolean routesToAgent() {
		return agent != null;
	}

	/** Whether inbound messages invoke an operation. */
	public boolean routesToOperation() {
		return operation != null;
	}

	/** Human-readable routing target for status output. */
	public String target() {
		return routesToAgent() ? "agent " + agent : "operation " + operation;
	}

	/** The fixed acknowledgement text, or null when replies are the default or off. */
	public String fixedReply() {
		return (reply instanceof AString s) ? s.toString() : null;
	}

	/** Whether replies are suppressed for the operation handler. */
	public boolean silent() {
		return CVMBool.FALSE.equals(reply);
	}

	/** Whether this Telegram user may talk to the bot. */
	public boolean allows(Long userId, String username) {
		if (open) return true;
		if (userId != null && allowIds.contains(userId)) return true;
		if (username != null && allowNames.contains(username.toLowerCase(Locale.ROOT))) return true;
		return false;
	}

	/**
	 * Parses and validates one {@code bots.<name>} entry.
	 *
	 * @param name   Config key
	 * @param cell   Entry value (must be a map)
	 * @param strict Reject unknown keys
	 * @throws IllegalArgumentException with an actionable message on any defect
	 */
	static BotSpec parse(String name, ACell cell, boolean strict) {
		String where = "adapters.telegram.bots." + name;
		if (!name.matches("[A-Za-z0-9_-]+")) {
			throw new IllegalArgumentException(where + ": bot name must match [A-Za-z0-9_-]+");
		}
		AMap<AString, ACell> m = RT.castMap(cell);
		if (m == null) throw new IllegalArgumentException(where + " must be an object");
		if (strict) {
			for (long i = 0; i < m.count(); i++) {
				ACell k = m.entryAt(i).getKey();
				if (!(k instanceof AString ks) || !KNOWN_KEYS.contains(ks)) {
					throw new IllegalArgumentException(where + ": unknown setting " + k
						+ " (known: token, user, agent, operation, reply, allow, open, parseMode, greeting)");
				}
			}
		}

		String token = optString(m, K_TOKEN, where);
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException(where + ".token is required — an s/NAME secret "
				+ "reference (recommended) or the literal bot token from @BotFather");
		}

		String userStr = optString(m, K_USER, where);
		if (userStr == null || userStr.isBlank()) {
			throw new IllegalArgumentException(where + ".user is required: the DID the bot acts as "
				+ "(the owner of the agent it routes to), or \"public\" for the venue's public principal");
		}
		if (!PUBLIC_USER.equals(userStr) && !userStr.startsWith("did:")) {
			throw new IllegalArgumentException(where + ".user must be a DID or \"public\": " + userStr);
		}

		// Handler: exactly one of agent (conversation) or operation (any op fed the
		// message record). One message, one handler; an operation may itself hand
		// off to an agent, log, or anything else.
		String agent = optString(m, K_AGENT, where);
		String operation = optString(m, K_OPERATION, where);
		if ((agent == null) == (operation == null)) {
			throw new IllegalArgumentException(where + ": exactly one of agent (an agent id in the "
				+ "bot user's namespace, routed via agent:chat) or operation (an operation reference "
				+ "invoked per message with the message record as input) is required");
		}
		if (agent != null && agent.isBlank()) throw new IllegalArgumentException(where + ".agent must not be blank");
		if (operation != null && operation.isBlank()) throw new IllegalArgumentException(where + ".operation must not be blank");

		ACell reply = m.get(K_REPLY);
		if (reply != null) {
			if (agent != null) {
				throw new IllegalArgumentException(where + ".reply applies to an operation handler; "
					+ "an agent conversation always replies");
			}
			if (!(reply instanceof CVMBool) && !(reply instanceof AString)) {
				throw new IllegalArgumentException(where + ".reply must be true (send the operation result), "
					+ "false (never reply) or a fixed acknowledgement string");
			}
			if (CVMBool.TRUE.equals(reply)) reply = null;   // the default, normalised
		}

		Set<Long> ids = new LinkedHashSet<>();
		Set<String> names = new LinkedHashSet<>();
		ACell allowCell = m.get(K_ALLOW);
		if (allowCell != null) {
			AVector<ACell> allow = RT.ensureVector(allowCell);
			if (allow == null) throw new IllegalArgumentException(where + ".allow must be an array of "
				+ "Telegram user ids or @usernames");
			for (long i = 0; i < allow.count(); i++) {
				ACell e = allow.get(i);
				CVMLong id = RT.ensureLong(e);
				if (id != null) {
					ids.add(id.longValue());
				} else if (e instanceof AString s) {
					String v = s.toString().trim();
					if (v.matches("-?\\d+")) {
						ids.add(Long.parseLong(v));
					} else {
						if (v.startsWith("@")) v = v.substring(1);
						if (v.isEmpty()) throw new IllegalArgumentException(where + ".allow: empty username");
						names.add(v.toLowerCase(Locale.ROOT));
					}
				} else {
					throw new IllegalArgumentException(where + ".allow entries must be Telegram user ids "
						+ "or @usernames, got " + e);
				}
			}
		}

		boolean open = false;
		ACell openCell = m.get(K_OPEN);
		if (openCell != null) {
			if (!(openCell instanceof CVMBool b)) throw new IllegalArgumentException(where + ".open must be a boolean");
			open = b.booleanValue();
		}

		String parseMode = optString(m, K_PARSE_MODE, where);
		if (parseMode != null && !PARSE_MODES.contains(parseMode)) {
			throw new IllegalArgumentException(where + ".parseMode must be one of Markdown, MarkdownV2, HTML"
				+ " (omit for plain text): " + parseMode);
		}

		String greeting = optString(m, K_GREETING, where);

		return new BotSpec(name, token, userStr, agent, operation, reply, ids, names, open, parseMode, greeting);
	}

	private static String optString(AMap<AString, ACell> m, AString key, String where) {
		ACell v = m.get(key);
		if (v == null) return null;
		if (!(v instanceof AString s)) {
			throw new IllegalArgumentException(where + "." + key + " must be a string");
		}
		return s.toString();
	}

	@Override
	public String toString() {
		// Never print the token: it may be a literal.
		return "BotSpec[" + name + " as " + userRef + " -> " + target() + "]";
	}
}

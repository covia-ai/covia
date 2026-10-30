package covia.adapter.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.messaging.ChannelAuthentication;
import covia.api.Fields;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.auth.AuthResult;

/**
 * Who a turn came from, as the {@code From} label element shows it (COG-20 §4,
 * AUTH.md §5.3): the caller the venue recorded with the turn and, when a
 * channel binding authenticated it, the authentication record. Rendered from
 * those and the agent's own identity alone — no venue state is consulted — so
 * a turn renders the same way every time and the cached prefix holds.
 */
final class TurnProvenance {
	private TurnProvenance() {}

	/** How a caller stands to the agent. */
	enum Relationship {
		SELF("self", "self"),
		OWNER("owner", "owner"),
		ANONYMOUS_OWNER("anonymous-owner", "anonymous owner"),
		SIBLING_AGENT("same-owner-agent", "same owner's agent"),
		SAME_OWNER("same-owner-principal", "same owner's principal"),
		VENUE("venue", "venue"),
		VENUE_AGENT("venue-agent", "venue's agent"),
		ANONYMOUS("anonymous", "anonymous"),
		OTHER_USER("other-user", "other user"),
		OTHER_AGENT("other-user-agent", "other user's agent");

		/** The xml attribute value. */
		final String token;
		/** The bracket and header wording. */
		final String words;

		Relationship(String token, String words) {
			this.token = token;
			this.words = words;
		}

		boolean namesAgent() {
			return this == SIBLING_AGENT || this == VENUE_AGENT || this == OTHER_AGENT;
		}
	}

	/** Ids and handles render only in this shape; anything else is left out. */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:@+/=-]{1,128}");
	private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	/**
	 * The identity part of a turn's provenance. A new element appears only when it
	 * changes; per-message fields are never part of it.
	 */
	record Key(AString caller, ACell method, ACell binding, ACell identity, ACell chat, ACell thread, ACell space) {}

	/** The agent's own principal, which turns start from. */
	static Key self(RequestContext ctx) {
		return new Key(ctx == null ? null : ctx.getCallerDID(), null, null, null, null, null, null);
	}

	/** A turn's key, or null for a turn stored without a caller. */
	static Key key(AMap<AString, ACell> turn) {
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		if (caller == null) return null;
		AMap<AString, ACell> auth = RT.ensureMap(turn.get(Fields.AUTHENTICATION));
		if (auth == null) return new Key(caller, null, null, null, null, null, null);
		AMap<AString, ACell> details = details(auth);
		return new Key(caller, RT.getIn(auth, AuthResult.AUTHENTICATION, AuthResult.METHOD),
			RT.getIn(auth, AuthResult.AUTHENTICATION, AuthResult.BINDING), auth.get(AuthResult.AUTHENTICATED_IDENTITY),
			RT.getIn(details, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_ID),
			RT.getIn(details, ChannelAuthentication.K_THREAD),
			RT.getIn(details, ChannelAuthentication.K_SPACE, ChannelAuthentication.K_ID));
	}

	/** How {@code caller} stands to the agent acting under {@code ctx}, from DIDs alone. */
	static Relationship relationship(Engine engine, RequestContext ctx, AString caller) {
		AString owner = (ctx != null) ? ctx.getUserDID() : null;
		AString self = (ctx != null) ? ctx.getCallerDID() : null;
		if (engine != null && engine.isVenuePrincipal(caller)) return Relationship.VENUE;
		if (engine != null && engine.isPublicPrincipal(caller)) {
			return caller.equals(owner) ? Relationship.ANONYMOUS_OWNER : Relationship.ANONYMOUS;
		}
		switch (Principals.relate(self, owner, caller)) {
			case SELF: return Relationship.SELF;
			case OWNER: return Relationship.OWNER;
			case SAME_USER:
				return Principals.agentIdOf(caller) != null ? Relationship.SIBLING_AGENT : Relationship.SAME_OWNER;
			default: {
				boolean agent = Principals.agentIdOf(caller) != null;
				if (engine != null && engine.isVenuePrincipal(Principals.userOf(caller))) {
					return agent ? Relationship.VENUE_AGENT : Relationship.VENUE;
				}
				return agent ? Relationship.OTHER_AGENT : Relationship.OTHER_USER;
			}
		}
	}

	/** The {@code From} element for a turn with a recorded caller, in the agent's dialect. */
	static String element(AString dialect, Engine engine, RequestContext ctx, AMap<AString, ACell> turn) {
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		Relationship relationship = relationship(engine, ctx, caller);
		AString agentId = relationship.namesAgent() ? Principals.agentIdOf(caller) : null;
		List<String> attributes = new ArrayList<>(List.of(
			"principal", caller.toString(), "relationship", relationship.token));
		if (agentId != null) attributes.addAll(List.of("agent", agentId.toString()));
		Channel channel = channel(turn);
		if (channel != null) channel.attributes(attributes);
		return Labels.renderAttributed(dialect, Labels.Kind.FROM, summary(relationship, agentId, caller, channel),
			attributes.toArray(new String[0]));
	}

	/** Who a task came from, as the outstanding-task message names its requester. */
	static String summary(Engine engine, RequestContext ctx, ACell task) {
		AMap<AString, ACell> map = RT.ensureMap(task);
		AString caller = (map == null) ? null : RT.ensureString(map.get(Fields.CALLER));
		if (caller == null) return null;
		Relationship relationship = relationship(engine, ctx, caller);
		AString agentId = relationship.namesAgent() ? Principals.agentIdOf(caller) : null;
		return summary(relationship, agentId, caller, channel(map));
	}

	private static String summary(Relationship relationship, AString agentId, AString caller, Channel channel) {
		StringBuilder sb = new StringBuilder(relationship.words);
		if (agentId != null) sb.append(' ').append(agentId);
		sb.append(' ').append(caller);
		if (channel != null) channel.describe(sb);
		return sb.toString();
	}

	private static AMap<AString, ACell> details(AMap<AString, ACell> auth) {
		return RT.ensureMap(RT.getIn(auth, AuthResult.AUTHENTICATION, AuthResult.DETAILS));
	}

	private static Channel channel(AMap<AString, ACell> turn) {
		AMap<AString, ACell> auth = RT.ensureMap(turn.get(Fields.AUTHENTICATION));
		if (auth == null) return null;
		AMap<AString, ACell> details = details(auth);
		String method = identifier(RT.getIn(auth, AuthResult.AUTHENTICATION, AuthResult.METHOD));
		if (method == null || details == null) return null;
		AString resource = RT.ensureString(RT.getIn(auth, AuthResult.AUTHENTICATION, AuthResult.BINDING));
		String binding = (resource == null) ? null
			: identifier(Strings.create(resource.toString().substring(resource.toString().lastIndexOf('/') + 1)));
		AString kind = RT.ensureString(RT.getIn(details, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_KIND));
		boolean knownKind = ChannelAuthentication.DIRECT.equals(kind) || ChannelAuthentication.GROUP.equals(kind)
			|| ChannelAuthentication.CHANNEL.equals(kind);
		return new Channel(method, binding,
			identifier(RT.getIn(details, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_ID)),
			handle(RT.getIn(details, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_HANDLE)),
			identifier(RT.getIn(details, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_ID)),
			knownKind ? kind.toString() : null,
			identifier(RT.getIn(details, ChannelAuthentication.K_THREAD)),
			identifier(RT.getIn(details, ChannelAuthentication.K_SPACE, ChannelAuthentication.K_ID)));
	}

	private static String identifier(ACell value) {
		return (value instanceof AString s && IDENTIFIER.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	private static String handle(ACell value) {
		return (value instanceof AString s && HANDLE.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	/** The rendered part of a channel record: validated ids, a handle and the chat kind. Never names. */
	private record Channel(String method, String binding, String sender, String handle, String chat,
			String kind, String thread, String space) {

		void attributes(List<String> out) {
			add(out, "method", method);
			add(out, "binding", binding);
			add(out, "sender", sender);
			add(out, "handle", handle);
			add(out, "chat", chat);
			add(out, "chat-kind", kind);
			add(out, "thread", thread);
			add(out, "space", space);
		}

		void describe(StringBuilder sb) {
			sb.append(" — ").append(method);
			if (binding != null) sb.append(' ').append(binding);
			sb.append(':');
			if (sender != null) sb.append(" sender ").append(sender);
			if (handle != null) sb.append(" @").append(handle);
			if (chat != null) {
				sb.append(sender != null ? ", " : " ");
				if (kind != null) sb.append(kind).append(' ');
				sb.append("chat ").append(chat);
			}
			if (thread != null) sb.append(", thread ").append(thread);
			if (space != null) sb.append(", space ").append(space);
		}

		private static void add(List<String> out, String name, String value) {
			if (value == null) return;
			out.add(name);
			out.add(value);
		}
	}
}

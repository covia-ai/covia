package covia.adapter.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.messaging.ChannelAuthentication;
import covia.api.Fields;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.auth.AuthResult;

/**
 * Who a turn came from, as the {@code From} label element shows it (COG-20 §4,
 * AUTH.md §5.3): the caller the venue recorded with the turn, and its route,
 * what carried the request to the agent ({@link RequestContext#getRoute}), one
 * segment per step. A turn stored before routes carries its channel's
 * authentication record instead, read as that channel's step. Rendered from
 * those and the agent's own identity alone — no venue state is consulted — so a
 * turn renders the same way every time and the cached prefix holds.
 *
 * <p>Nothing stored is trusted to be well formed. Only values in a conservative
 * shape are shown, and names never are. A route that cannot be read shows as
 * an unreadable step, never as no route, so a request that something carried
 * never looks as though it came direct.</p>
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
		OTHER_AGENT("other-user-agent", "other user's agent"),
		/** A recorded caller that is not a DID, or too long to show. */
		UNKNOWN("unknown", "unreadable sender");

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

	/** Ids, names of what carried a request, and adapter names render only in this shape; anything else is left out. */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:@+/=-]{1,128}");
	private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	/** Longest caller the element shows, once encoded; a longer one is unreadable. */
	private static final int MAX_SHOWN_DID = 512;
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();

	/**
	 * {@code did} as the element shows it: DID syntax as it is (W3C DID Core:
	 * letters, digits, {@code . - _ :} and percent-encoding), any other
	 * character percent-encoded as UTF-8, so nothing in a principal's id, such
	 * as an agent's, can close the label or pose as a route segment. Null when
	 * it is not a DID, or too long to show.
	 */
	static String shownDid(AString did) {
		String value = did.toString();
		return value.startsWith("did:") ? encoded(value) : null;
	}

	/** {@code value} with every character outside DID syntax percent-encoded, or null when longer than can be shown. */
	private static String encoded(String value) {
		StringBuilder sb = new StringBuilder();
		for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			boolean kept = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '.' || c == '-' || c == '_' || c == ':' || c == '%';
			if (kept) sb.append((char) c);
			else sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
			if (sb.length() > MAX_SHOWN_DID) return null;
		}
		return sb.toString();
	}

	/**
	 * One entry of a turn's route as the element shows it: a step, with only its
	 * values that passed their checks; a run of steps folded out of a long
	 * route; or an entry that could not be read.
	 */
	record Segment(String adapter, String name, String sender, String handle, String chat, String kind,
			String thread, String space, long folded) {
		static final Segment UNREADABLE = new Segment(null, null, null, null, null, null, null, null, 0);

		static Segment folded(long count) {
			return new Segment(null, null, null, null, null, null, null, null, count);
		}

		void attributes(List<String[]> children) {
			if (folded > 0) {
				children.add(new String[] {"via", "elided", Long.toString(folded)});
				return;
			}
			if (adapter == null) {
				children.add(new String[] {"via", "unreadable", "true"});
				return;
			}
			List<String> child = new ArrayList<>(List.of("via", "adapter", adapter));
			add(child, "name", name);
			add(child, "sender", sender);
			add(child, "handle", handle);
			add(child, "chat", chat);
			add(child, "chat-kind", kind);
			add(child, "thread", thread);
			add(child, "space", space);
			children.add(child.toArray(new String[0]));
		}

		void describe(StringBuilder sb) {
			sb.append(" — ");
			if (folded > 0) {
				sb.append(folded).append(folded == 1 ? " more step" : " more steps");
				return;
			}
			if (adapter == null) {
				sb.append("unreadable route step");
				return;
			}
			sb.append(adapter);
			if (name != null) sb.append(' ').append(name);
			List<String> details = new ArrayList<>();
			if (sender != null) details.add("sender " + sender + ((handle != null) ? " @" + handle : ""));
			else if (handle != null) details.add("@" + handle);
			if (chat != null) details.add(((kind != null) ? kind + " " : "") + "chat " + chat);
			if (thread != null) details.add("thread " + thread);
			if (space != null) details.add("space " + space);
			if (!details.isEmpty()) sb.append(": ").append(String.join(", ", details));
		}

		private static void add(List<String> out, String name, String value) {
			if (value == null) return;
			out.add(name);
			out.add(value);
		}
	}

	/**
	 * The identity part of a turn's provenance: its caller and what its route
	 * shows. A new element appears only when it changes; per-message fields are
	 * never part of it.
	 */
	record Key(AString caller, List<Segment> route) {}

	/** The agent's own principal, which turns start from. */
	static Key self(RequestContext ctx) {
		return new Key(ctx == null ? null : ctx.getCallerDID(), List.of());
	}

	/** A turn's key, or null for a turn stored without a caller. */
	static Key key(AMap<AString, ACell> turn) {
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		return (caller == null) ? null : new Key(caller, route(turn));
	}

	/** How {@code caller} stands to the agent acting under {@code ctx}, from DIDs alone. */
	static Relationship relationship(Engine engine, RequestContext ctx, AString caller) {
		if (shownDid(caller) == null) return Relationship.UNKNOWN;
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
		List<String> attributes = new ArrayList<>();
		if (relationship != Relationship.UNKNOWN) attributes.addAll(List.of("principal", shownDid(caller)));
		attributes.addAll(List.of("relationship", relationship.token));
		if (agentId != null) attributes.addAll(List.of("agent", encoded(agentId.toString())));
		List<Segment> route = route(turn);
		List<String[]> children = new ArrayList<>();
		for (Segment segment : route) segment.attributes(children);
		return Labels.renderAttributed(dialect, Labels.Kind.FROM, summary(relationship, agentId, caller, route),
			children, attributes.toArray(new String[0]));
	}

	/** Who a task came from, as the outstanding-task message names its requester. */
	static String summary(Engine engine, RequestContext ctx, ACell task) {
		AMap<AString, ACell> map = RT.ensureMap(task);
		AString caller = (map == null) ? null : RT.ensureString(map.get(Fields.CALLER));
		if (caller == null) return null;
		Relationship relationship = relationship(engine, ctx, caller);
		AString agentId = relationship.namesAgent() ? Principals.agentIdOf(caller) : null;
		return summary(relationship, agentId, caller, route(map));
	}

	private static String summary(Relationship relationship, AString agentId, AString caller, List<Segment> route) {
		StringBuilder sb = new StringBuilder(relationship.words);
		if (relationship != Relationship.UNKNOWN) {
			if (agentId != null) sb.append(' ').append(encoded(agentId.toString()));
			sb.append(' ').append(shownDid(caller));
		}
		for (Segment segment : route) segment.describe(sb);
		return sb.toString();
	}

	/**
	 * What a turn's or task's route shows, in order: empty when it came direct.
	 * A route longer than {@link RequestContext#ROUTE_MAX} shows its first
	 * {@link RequestContext#ROUTE_HEAD} entries, how many it folds, and its latest.
	 */
	static List<Segment> route(AMap<AString, ACell> turn) {
		ACell route = turn.get(Fields.ROUTE);
		if (route == null) {
			ACell authentication = turn.get(Fields.AUTHENTICATION);
			return (authentication == null) ? List.of() : List.of(channelStep(authentication));
		}
		if (!(route instanceof AVector<?> entries) || entries.count() == 0) return List.of(Segment.UNREADABLE);
		long count = entries.count();
		List<Segment> out = new ArrayList<>();
		if (count <= RequestContext.ROUTE_MAX) {
			for (long i = 0; i < count; i++) out.add(segment(entries.get(i)));
			return out;
		}
		long tail = RequestContext.ROUTE_MAX - RequestContext.ROUTE_HEAD - 1;
		for (long i = 0; i < RequestContext.ROUTE_HEAD; i++) out.add(segment(entries.get(i)));
		out.add(Segment.folded(count - RequestContext.ROUTE_HEAD - tail));
		for (long i = count - tail; i < count; i++) out.add(segment(entries.get(i)));
		return out;
	}

	/** One route entry as shown: a step naming the adapter that carried the request, a folded run, or unreadable. */
	private static Segment segment(ACell entry) {
		AMap<AString, ACell> step = RT.ensureMap(entry);
		if (step == null) return Segment.UNREADABLE;
		ACell adapter = step.get(Fields.ADAPTER);
		if (adapter == null && step.get(RequestContext.ROUTE_ELIDED) instanceof CVMLong folded && folded.longValue() > 0) {
			return Segment.folded(folded.longValue());
		}
		String name = identifier(adapter);
		return (name == null) ? Segment.UNREADABLE : shown(name, step);
	}

	/** A turn stored before routes: its channel's authentication record, read as the channel's step. */
	private static Segment channelStep(ACell authentication) {
		String method = identifier(RT.getIn(authentication, AuthResult.AUTHENTICATION, AuthResult.METHOD));
		return (method == null) ? Segment.UNREADABLE : shown(method, ChannelAuthentication.step(authentication));
	}

	/** Step {@code step} of adapter {@code adapter}: its name, and a channel's ids, handle and chat kind. Never other names. */
	private static Segment shown(String adapter, AMap<AString, ACell> step) {
		AString kind = RT.ensureString(RT.getIn(step, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_KIND));
		boolean knownKind = ChannelAuthentication.DIRECT.equals(kind) || ChannelAuthentication.GROUP.equals(kind)
			|| ChannelAuthentication.CHANNEL.equals(kind);
		return new Segment(adapter, identifier(step.get(Fields.NAME)),
			identifier(RT.getIn(step, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_ID)),
			handle(RT.getIn(step, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_HANDLE)),
			identifier(RT.getIn(step, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_ID)),
			knownKind ? kind.toString() : null,
			identifier(step.get(ChannelAuthentication.K_THREAD)),
			identifier(RT.getIn(step, ChannelAuthentication.K_SPACE, ChannelAuthentication.K_ID)), 0);
	}

	private static String identifier(ACell value) {
		return (value instanceof AString s && IDENTIFIER.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	private static String handle(ACell value) {
		return (value instanceof AString s && HANDLE.matcher(s.toString()).matches()) ? s.toString() : null;
	}
}

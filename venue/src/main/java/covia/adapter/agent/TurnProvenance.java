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

		/** The relationship already identifies self and the agent's own user.
		 * Their internal principal stays in the recorded turn, not the prompt. */
		boolean namesPrincipal() {
			return this != SELF && this != OWNER && this != ANONYMOUS_OWNER && this != UNKNOWN;
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

	/** A step's owner or caller that is named but cannot be shown. */
	private static final String UNREADABLE_OWNER = "unknown";

	/**
	 * One entry of a turn's route as the element shows it: a step, with only its
	 * values that passed their checks; a run of steps folded out of a long
	 * route; or an entry that could not be read. A step's {@code owner} is whose
	 * token, binding or callable it is, as shown, {@link #UNREADABLE_OWNER} when
	 * it names one that cannot be shown, or null when it names none. Its
	 * {@code caller} is whom a callable carried the request for, shown the same
	 * way.
	 */
	record Segment(String adapter, String name, String owner, String caller, String sender, String handle, String chat,
			String kind, String thread, String space, long folded) {
		static final Segment UNREADABLE = new Segment(null, null, null, null, null, null, null, null, null, null, 0);

		static Segment folded(long count) {
			return new Segment(null, null, null, null, null, null, null, null, null, null, count);
		}

		/**
		 * The owner this step shows to an agent whose owner shows as
		 * {@code agentOwner}: none for the agent owner's own, so a name alone is
		 * always the owner's, and otherwise whose it is.
		 */
		private String shownOwner(String agentOwner) {
			return (owner == null || owner.equals(agentOwner)) ? null : owner;
		}

		void attributes(List<String[]> children, String agentOwner) {
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
			add(child, "owner", shownOwner(agentOwner));
			add(child, "caller", caller);
			add(child, "sender", sender);
			add(child, "handle", handle);
			add(child, "chat", chat);
			add(child, "chat-kind", kind);
			add(child, "thread", thread);
			add(child, "space", space);
			children.add(child.toArray(new String[0]));
		}

		void describe(StringBuilder sb, String agentOwner) {
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
			String shown = shownOwner(agentOwner);
			if (shown != null) sb.append(" of ").append(UNREADABLE_OWNER.equals(shown) ? "unknown owner" : shown);
			List<String> details = new ArrayList<>();
			if (caller != null) details.add(UNREADABLE_OWNER.equals(caller) ? "unknown caller" : "caller " + caller);
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
	 * shows, or, for a turn whose route names where its request started
	 * ({@link #namesStart}), its caller and route records as stored.
	 */
	record Key(AString caller, List<Segment> route, ACell records) {}

	/**
	 * Whether {@code turn}'s route names where its request started: each
	 * record names the adapter that wrote it as {@code via} and the principal
	 * it vouches for. Turns recorded before routes took this form keep the
	 * rendering they always had, so no stored context is shown differently;
	 * that includes a route that is present but empty or unreadable, which
	 * shows as an unreadable step, never as direct.
	 */
	static boolean namesStart(AMap<AString, ACell> turn) {
		return turn.get(Fields.ROUTE) instanceof AVector<?> records && records.count() > 0
			&& records.get(0) instanceof AMap<?, ?> first && first.containsKey(Fields.VIA);
	}

	/**
	 * The agent whose turns {@code ctx} renders: the agent whose execution it
	 * drives, since its harness acts as the agent's controller; outside an
	 * agent's execution, its caller.
	 */
	private static AString agentOf(RequestContext ctx) {
		if (ctx == null) return null;
		RequestContext.ExecutionScope execution = ctx.getExecutionScope();
		return (execution.agentId() == null) ? ctx.getAuthorityDID()
			: Principals.agentDID(execution.ownerDID(), execution.agentId());
	}

	/** The account of the agent whose turns {@code ctx} renders ({@link #agentOf}). */
	private static AString ownerOf(RequestContext ctx) {
		if (ctx == null) return null;
		RequestContext.ExecutionScope execution = ctx.getExecutionScope();
		return (execution.agentId() == null) ? ctx.getUserDID() : execution.ownerDID();
	}

	/** A turn's key, or null for a turn stored without a caller. */
	static Key key(AMap<AString, ACell> turn) {
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		if (caller == null) return null;
		return namesStart(turn) ? new Key(caller, List.of(), turn.get(Fields.ROUTE)) : new Key(caller, route(turn), null);
	}

	/**
	 * Only consecutive direct-owner input omits an element. Every other source
	 * is labelled on every incoming turn, even when its route is unchanged.
	 * An absent or legacy source does not establish direct-owner continuity.
	 */
	static boolean continues(Engine engine, RequestContext ctx, Key last, Key key) {
		return last != null && key != null && ownerDirect(engine, ctx, last) && ownerDirect(engine, ctx, key);
	}

	/**
	 * Whether {@code key} is the agent's owner chatting directly: one {@code auth}
	 * record with no ingress transport, vouching for the owner, who is the
	 * turn's caller and never the venue's public principal. Read from the
	 * records' structure, never their text.
	 */
	static boolean ownerDirect(Engine engine, RequestContext ctx, Key key) {
		AMap<AString, ACell> only = onlyRecord(key);
		if (only == null || !AUTH.equals(only.get(Fields.VIA)) || only.containsKey(Fields.TRANSPORT)) return false;
		AString owner = ownerOf(ctx);
		AString principal = RT.ensureString(only.get(Fields.PRINCIPAL));
		if (owner == null || !owner.equals(principal) || !owner.equals(key.caller())) return false;
		return engine == null || !engine.isPublicPrincipal(owner);
	}

	/** The one record of {@code key}'s route, or null when it has another number or is not that form. */
	private static AMap<AString, ACell> onlyRecord(Key key) {
		if (!(key.records() instanceof AVector<?> records) || records.count() != 1) return null;
		return RT.ensureMap(records.get(0));
	}

	/** The adapter whose sign-in record can establish direct-owner input. */
	private static final AString AUTH = convex.core.data.Strings.intern(covia.adapter.AuthAdapter.NAME);

	/** How {@code caller} stands to the agent acting under {@code ctx}, from DIDs alone. */
	static Relationship relationship(Engine engine, RequestContext ctx, AString caller) {
		if (shownDid(caller) == null) return Relationship.UNKNOWN;
		AString owner = ownerOf(ctx);
		AString self = agentOf(ctx);
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
		if (namesStart(turn)) return Labels.renderSentence(dialect, Labels.Kind.FROM_VENUE, sentence(engine, ctx, turn));
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		Relationship relationship = relationship(engine, ctx, caller);
		AString agentId = relationship.namesAgent() ? Principals.agentIdOf(caller) : null;
		List<String> attributes = new ArrayList<>();
		if (relationship.namesPrincipal()) attributes.addAll(List.of("principal", shownDid(caller)));
		attributes.addAll(List.of("relationship", relationship.token));
		if (agentId != null) attributes.addAll(List.of("agent", encoded(agentId.toString())));
		List<Segment> route = route(turn);
		String agentOwner = agentOwner(ctx);
		List<String[]> children = new ArrayList<>();
		for (Segment segment : route) segment.attributes(children, agentOwner);
		return Labels.renderAttributed(dialect, Labels.Kind.FROM, summary(relationship, agentId, caller, route, agentOwner),
			children, attributes.toArray(new String[0]));
	}

	/** Who a task came from, as the outstanding-task message names its requester. */
	static String summary(Engine engine, RequestContext ctx, ACell task) {
		AMap<AString, ACell> map = RT.ensureMap(task);
		AString caller = (map == null) ? null : RT.ensureString(map.get(Fields.CALLER));
		if (caller == null) return null;
		if (namesStart(map)) return sentence(engine, ctx, map);
		Relationship relationship = relationship(engine, ctx, caller);
		AString agentId = relationship.namesAgent() ? Principals.agentIdOf(caller) : null;
		return summary(relationship, agentId, caller, route(map), agentOwner(ctx));
	}

	private static String summary(Relationship relationship, AString agentId, AString caller, List<Segment> route,
			String agentOwner) {
		StringBuilder sb = new StringBuilder(relationship.words);
		if (relationship != Relationship.UNKNOWN) {
			if (agentId != null) sb.append(' ').append(encoded(agentId.toString()));
			if (relationship.namesPrincipal()) sb.append(' ').append(shownDid(caller));
		}
		for (Segment segment : route) segment.describe(sb, agentOwner);
		return sb.toString();
	}

	/** The agent's owner as a step's owner shows, so its own tokens, bindings and callables go by name alone; null when unknown. */
	private static String agentOwner(RequestContext ctx) {
		AString owner = ownerOf(ctx);
		return (owner == null) ? null : shownDid(owner);
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

	/**
	 * Step {@code step} of adapter {@code adapter}: its name, owner and caller,
	 * and a channel's ids, handle and chat kind. Never other names.
	 */
	private static Segment shown(String adapter, AMap<AString, ACell> step) {
		AString kind = RT.ensureString(RT.getIn(step, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_KIND));
		boolean knownKind = ChannelAuthentication.DIRECT.equals(kind) || ChannelAuthentication.GROUP.equals(kind)
			|| ChannelAuthentication.CHANNEL.equals(kind);
		return new Segment(adapter, identifier(step.get(Fields.NAME)), principal(step, Fields.OWNER),
			principal(step, Fields.CALLER),
			identifier(RT.getIn(step, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_ID)),
			handle(RT.getIn(step, ChannelAuthentication.K_SENDER, ChannelAuthentication.K_HANDLE)),
			identifier(RT.getIn(step, ChannelAuthentication.K_CHAT, ChannelAuthentication.K_ID)),
			knownKind ? kind.toString() : null,
			identifier(step.get(ChannelAuthentication.K_THREAD)),
			identifier(RT.getIn(step, ChannelAuthentication.K_SPACE, ChannelAuthentication.K_ID)), 0);
	}

	/**
	 * The principal {@code step} names as {@code key}, as shown: null when it
	 * names none, {@link #UNREADABLE_OWNER} when it names one, null included,
	 * that cannot be shown, so a recorded principal is never left out.
	 */
	private static String principal(AMap<AString, ACell> step, AString key) {
		if (!step.containsKey(key)) return null;
		String shown = (step.get(key) instanceof AString did) ? shownDid(did) : null;
		return (shown != null) ? shown : UNREADABLE_OWNER;
	}

	private static String identifier(ACell value) {
		return (value instanceof AString s && IDENTIFIER.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	private static String handle(ACell value) {
		return (value instanceof AString s && HANDLE.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	// ========== Routes that name where their request started ==========

	/** What a route that cannot be read, or that does not end at its turn's caller, shows as. */
	static final String UNREADABLE_ROUTE = "a route the venue cannot read";
	/** What a record that cannot be read shows as. */
	static final String UNREADABLE_RECORD = "an unreadable route record";
	/** The words that mark a principal other than the agent's owner. */
	static final String NOT_OWNER = "not your owner";
	/** Longest text an adapter's record shows, in characters. */
	static final int MAX_TEXT = 300;

	/**
	 * The sentence a turn whose route names where its request started shows
	 * ({@link #namesStart}): each record in order, the first as whoever the
	 * request started with and each later one as whom it then acted as. The
	 * venue writes the principal, marked as the agent's owner or not, and
	 * {@code via} and the adapter's name; the adapter's own text follows in
	 * brackets. A route that does not end at the turn's caller shows as
	 * unreadable, never as the owner.
	 */
	static String sentence(Engine engine, RequestContext ctx, AMap<AString, ACell> turn) {
		AString caller = RT.ensureString(turn.get(Fields.CALLER));
		if (!(turn.get(Fields.ROUTE) instanceof AVector<?> records) || records.count() == 0) return UNREADABLE_ROUTE;
		AMap<AString, ACell> last = RT.ensureMap(records.get(records.count() - 1));
		if (last == null || caller == null || !caller.equals(last.get(Fields.PRINCIPAL))) return UNREADABLE_ROUTE;
		List<String> parts = new ArrayList<>();
		for (long i = 0; i < records.count(); i++) {
			String prefix = (i == 0) ? "" : "acting as ";
			AMap<AString, ACell> record = RT.ensureMap(records.get(i));
			if (record != null && !record.containsKey(Fields.VIA)
					&& record.get(RequestContext.ROUTE_ELIDED) instanceof CVMLong folded && folded.longValue() > 0) {
				parts.add("then " + folded.longValue() + (folded.longValue() == 1 ? " more step" : " more steps"));
				continue;
			}
			String via = (record == null) ? null : identifier(record.get(Fields.VIA));
			if (via == null) {
				parts.add(prefix + UNREADABLE_RECORD);
				continue;
			}
			AString principal = RT.ensureString(record.get(Fields.PRINCIPAL));
			String who = who(engine, ctx, principal, AUTH.equals(record.get(Fields.VIA)));
			String text = text(engine, ctx, record);
			parts.add(prefix + who + (who.endsWith(NOT_OWNER) ? ", via " : " via ") + via
				+ ((text == null) ? "" : " (" + text + ")"));
		}
		return String.join("; ", parts);
	}

	/**
	 * {@code principal} as a sentence names it: the agent's owner, the agent
	 * itself, an agent of the owner or of another, the venue, an anonymous
	 * caller or another principal, each but the owner marked as not the owner.
	 * The venue's public principal is never the owner. The owner and the agent
	 * itself show without their DID, as the older element shows them, so
	 * equivalent agents of different owners see the same words. A principal a door
	 * authenticated ({@code signedIn}) is named by its DID alone, since a DID's
	 * shape says nothing of whose agent it is; elsewhere the venue recorded the
	 * principal itself.
	 */
	private static String who(Engine engine, RequestContext ctx, AString principal, boolean signedIn) {
		String did = (principal == null) ? null : shownDid(principal);
		if (did == null) return "an unreadable principal, " + NOT_OWNER;
		if (engine != null && engine.isPublicPrincipal(principal)) return "an anonymous caller, " + NOT_OWNER;
		AString owner = ownerOf(ctx);
		if (principal.equals(agentOf(ctx)) && !principal.equals(owner)) return "you";
		if (principal.equals(owner)) return "your owner";
		if (engine != null && engine.isVenuePrincipal(principal)) return "the venue " + did + ", " + NOT_OWNER;
		AString agentId = signedIn ? null : Principals.agentIdOf(principal);
		String shownId = (agentId == null) ? null : encoded(agentId.toString());
		if (shownId != null) {
			boolean owners = owner != null && principal.equals(Principals.agentDID(owner, agentId));
			return owners ? "an agent belonging to your owner (\"" + shownId + "\", " + did + "), " + NOT_OWNER
				: "an agent of another user (\"" + shownId + "\", " + did + "), " + NOT_OWNER;
		}
		return did + ", " + NOT_OWNER;
	}

	/** Whose something is, as a record's text says: "your owner's", or the principal's. */
	private static String whose(Engine engine, RequestContext ctx, AString principal) {
		String did = (principal == null) ? null : shownDid(principal);
		if (did == null) return "an unreadable principal's";
		if (engine != null && engine.isPublicPrincipal(principal)) return "an anonymous caller's";
		if (principal.equals(ownerOf(ctx))) return "your owner's";
		if (engine != null && engine.isVenuePrincipal(principal)) return "the venue's";
		return did + "'s";
	}

	/**
	 * A record's own text ({@link covia.venue.RouteText}), each part shown in its
	 * checked shape, or, with none, the record's name, quoted. Null when it
	 * shows nothing.
	 */
	private static String text(Engine engine, RequestContext ctx, AMap<AString, ACell> record) {
		ACell stored = record.get(Fields.TEXT);
		if (stored == null) {
			String name = identifier(record.get(Fields.NAME));
			return (name == null) ? null : "\"" + name + "\"";
		}
		if (!(stored instanceof AVector<?> parts)) return "unreadable text";
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < parts.count() && i < covia.venue.RouteText.MAX_PARTS; i++) {
			ACell part = parts.get(i);
			if (part instanceof AString words) {
				sb.append(words.toString().isBlank() ? " " : spaced(words.toString()));
				continue;
			}
			AMap<AString, ACell> slot = RT.ensureMap(part);
			if (slot == null) continue;
			if (slot.containsKey(covia.venue.RouteText.NAME)) {
				String name = identifier(slot.get(covia.venue.RouteText.NAME));
				sb.append((name == null) ? "an unreadable name" : "\"" + name + "\"");
			} else if (slot.containsKey(covia.venue.RouteText.ID)) {
				String id = identifier(slot.get(covia.venue.RouteText.ID));
				sb.append((id == null) ? "unreadable" : id);
			} else if (slot.containsKey(covia.venue.RouteText.HANDLE)) {
				String handle = handle(slot.get(covia.venue.RouteText.HANDLE));
				if (handle != null) sb.append('@').append(handle);
			} else if (slot.get(covia.venue.RouteText.TIME) instanceof CVMLong millis) {
				sb.append(TIME_FORMAT.format(java.time.Instant.ofEpochMilli(millis.longValue())));
			} else if (slot.containsKey(covia.venue.RouteText.WHO)) {
				sb.append(who(engine, ctx, RT.ensureString(slot.get(covia.venue.RouteText.WHO)), false));
			} else if (slot.containsKey(covia.venue.RouteText.WHOSE)) {
				sb.append(whose(engine, ctx, RT.ensureString(slot.get(covia.venue.RouteText.WHOSE))));
			}
		}
		String shown = sb.toString().replaceAll(" {2,}", " ").trim();
		if (shown.length() > MAX_TEXT) shown = shown.substring(0, MAX_TEXT);
		return shown.isEmpty() ? null : shown;
	}

	/** Stored wording cleaned again as it is shown, keeping a space at either end where it had one. */
	private static String spaced(String words) {
		String cleaned = covia.venue.RouteText.clean(words);
		String lead = Character.isWhitespace(words.charAt(0)) ? " " : "";
		String trail = Character.isWhitespace(words.charAt(words.length() - 1)) ? " " : "";
		return lead + cleaned + trail;
	}

	private static final java.time.format.DateTimeFormatter TIME_FORMAT =
		java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC);
}

package covia.venue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Principals;

/**
 * Who a request came from and how it reached here, as text for whoever reads
 * it (AUTHORITY.md, Provenance): read from a stored record's {@code caller}
 * and {@code route} ({@link RequestContext#getRoute}) and worded for a
 * {@link Reader}, an agent or a person. The venue names each principal as the
 * reader would refer to it and joins the records; each adapter's own text
 * ({@link RouteText}) says the rest, with its parts that depend on the reader
 * worded for that reader. No adapter's meaning is known here.
 *
 * <p>Nothing stored is trusted to be well formed. Only values in a checked
 * shape are shown, and a route that cannot be read shows as such, never as no
 * route, so a request that something carried never reads as direct.</p>
 */
public final class Provenance {

	/** What a route that cannot be read, or that does not end at its record's caller, shows as. */
	public static final String UNREADABLE_ROUTE = "an unknown source: the venue cannot read this route";
	/** What a record that cannot be read shows as. */
	public static final String UNREADABLE_RECORD = "an unreadable route record";
	/** What a principal that cannot be shown is named. */
	public static final String UNREADABLE_PRINCIPAL = "an unreadable principal";
	/** What a path that cannot be shown is named. */
	public static final String UNREADABLE_REF = "an unreadable reference";
	/** What the venue's public principal is named. */
	public static final String ANONYMOUS = "an anonymous caller";
	/** What the venue is named. */
	public static final String VENUE = "the venue";
	/** What the reader is named. */
	public static final String YOU = "you";
	/** What an agent reader's owner is named. */
	public static final String YOUR_OWNER = "your owner";
	/** Longest text an adapter's record shows, in characters. */
	public static final int MAX_TEXT = 300;

	/** Ids, names, paths and adapter names show only in this shape. */
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.:@+/=-]{1,128}");
	private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
	/** Longest DID shown, once encoded; a longer one is unreadable. */
	private static final int MAX_SHOWN_DID = 512;
	private static final char[] HEX = "0123456789ABCDEF".toCharArray();
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final AString caller;
	private final ACell route;
	/** Whether the record carries a binding's authentication record and no route: stored before routes. */
	private final boolean authenticationOnly;

	private Provenance(AString caller, ACell route, boolean authenticationOnly) {
		this.caller = caller;
		this.route = route;
		this.authenticationOnly = authenticationOnly;
	}

	/**
	 * The provenance {@code record} keeps: a stored turn's, task's or approval
	 * request's {@code caller} and {@code route}. Reads no venue state.
	 */
	public static Provenance of(ACell record) {
		ACell route = RT.getIn(record, Fields.ROUTE);
		return new Provenance(RT.ensureString(RT.getIn(record, Fields.CALLER)), route,
			route == null && RT.getIn(record, Fields.AUTHENTICATION) != null);
	}

	/**
	 * Whether its route names where its request started: a first record that is
	 * a direct start or names the adapter that wrote it. A route recorded
	 * before routes took this form does not.
	 */
	public boolean namesStart() {
		return route instanceof AVector<?> records && records.count() > 0
			&& records.get(0) instanceof AMap<?, ?> first
			&& (first.containsKey(Fields.VIA) || first.containsKey(RequestContext.ROUTE_DIRECT));
	}

	/**
	 * The principal its request started with directly, nothing having carried
	 * it, when that is its whole route; otherwise null.
	 */
	public AString direct() {
		if (!(route instanceof AVector<?> records) || records.count() != 1) return null;
		AMap<AString, ACell> only = RT.ensureMap(records.get(0));
		return isDirect(only) ? RT.ensureString(only.get(Fields.PRINCIPAL)) : null;
	}

	/**
	 * The one principal its whole route names: its caller, when every record
	 * vouches for the caller and each is a readable step, a direct start or one
	 * naming the adapter that wrote it, with nothing folded out; otherwise null.
	 * Read from the records' structure, never their text.
	 */
	public AString onlyPrincipal() {
		if (!namesStart() || caller == null) return null;
		AVector<?> records = (AVector<?>) route;
		for (long i = 0; i < records.count(); i++) {
			AMap<AString, ACell> record = RT.ensureMap(records.get(i));
			if (record == null || !caller.equals(record.get(Fields.PRINCIPAL))) return null;
			boolean readable = (i == 0 && isDirect(record)) || shownIdentifier(record.get(Fields.VIA)) != null;
			if (!readable) return null;
		}
		return caller;
	}

	/**
	 * Whether {@code record} is a direct start ({@link RequestContext#directRecord}),
	 * which names no adapter. A route stored before in-process requests were
	 * marked direct shows one as the sign-in it was written as then, with no
	 * transport.
	 */
	private static boolean isDirect(AMap<AString, ACell> record) {
		if (record == null) return false;
		if (record.containsKey(Fields.VIA)) {
			return RequestContext.SIGN_IN.equals(record.get(Fields.VIA)) && !record.containsKey(Fields.TRANSPORT);
		}
		return CVMBool.TRUE.equals(record.get(RequestContext.ROUTE_DIRECT));
	}

	/**
	 * Who it came from and how, worded for {@code reader}, without a leading
	 * "From": each record in order, the first as whoever the request started
	 * with and each later one as whom it then acted as, each principal named
	 * by the venue and followed by {@code via}, the adapter's name and the
	 * adapter's own text in brackets. A record with no route names only its
	 * caller. A route that cannot be read, or that does not end at the caller,
	 * shows as {@link #UNREADABLE_ROUTE}; a long one shows the steps it folded
	 * as a count.
	 */
	public String text(Reader reader) {
		if (route == null && !authenticationOnly) return (caller == null) ? UNREADABLE_ROUTE : reader.who(caller, false);
		if (!namesStart()) return UNREADABLE_ROUTE;
		AVector<?> records = (AVector<?>) route;
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
			AString principal = (record == null) ? null : RT.ensureString(record.get(Fields.PRINCIPAL));
			if (i == 0 && isDirect(record)) {
				parts.add(reader.who(principal, false) + " directly");
				continue;
			}
			String via = (record == null) ? null : shownIdentifier(record.get(Fields.VIA));
			if (via == null) {
				parts.add(prefix + UNREADABLE_RECORD);
				continue;
			}
			String text = text(reader, record);
			parts.add(prefix + reader.who(principal, RequestContext.SIGN_IN.equals(record.get(Fields.VIA)))
				+ " via " + via + ((text == null) ? "" : " (" + text + ")"));
		}
		return String.join("; ", parts);
	}

	/**
	 * A record's own text ({@link RouteText}), each part shown in its checked
	 * shape and worded for {@code reader}, or, with none, the record's name,
	 * quoted. Null when it shows nothing.
	 */
	private static String text(Reader reader, AMap<AString, ACell> record) {
		ACell stored = record.get(Fields.TEXT);
		if (stored == null) {
			String name = shownIdentifier(record.get(Fields.NAME));
			return (name == null) ? null : "\"" + name + "\"";
		}
		if (!(stored instanceof AVector<?> parts)) return "unreadable text";
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < parts.count() && i < RouteText.MAX_PARTS; i++) {
			ACell part = parts.get(i);
			if (part instanceof AString words) {
				sb.append(words.toString().isBlank() ? " " : spaced(words.toString()));
				continue;
			}
			AMap<AString, ACell> slot = RT.ensureMap(part);
			if (slot == null) continue;
			if (slot.containsKey(RouteText.NAME)) {
				String name = shownIdentifier(slot.get(RouteText.NAME));
				sb.append((name == null) ? "an unreadable name" : "\"" + name + "\"");
			} else if (slot.containsKey(RouteText.ID)) {
				String id = shownIdentifier(slot.get(RouteText.ID));
				sb.append((id == null) ? "unreadable" : id);
			} else if (slot.containsKey(RouteText.HANDLE)) {
				String handle = shownHandle(slot.get(RouteText.HANDLE));
				if (handle != null) sb.append('@').append(handle);
			} else if (slot.get(RouteText.TIME) instanceof CVMLong millis) {
				sb.append(TIME_FORMAT.format(Instant.ofEpochMilli(millis.longValue())));
			} else if (slot.containsKey(RouteText.WHO)) {
				sb.append(reader.who(RT.ensureString(slot.get(RouteText.WHO)), false));
			} else if (slot.containsKey(RouteText.WHOSE)) {
				sb.append(reader.whose(RT.ensureString(slot.get(RouteText.WHOSE))));
			} else if (slot.containsKey(RouteText.REF)) {
				sb.append(reader.ref(RT.ensureString(slot.get(Fields.OWNER)), slot.get(RouteText.REF)));
			}
		}
		String shown = sb.toString().replaceAll(" {2,}", " ").trim();
		if (shown.length() > MAX_TEXT) shown = shown.substring(0, MAX_TEXT);
		return shown.isEmpty() ? null : shown;
	}

	/** Stored wording cleaned again as it is shown, keeping a space at either end where it had one. */
	private static String spaced(String words) {
		String cleaned = RouteText.clean(words);
		String lead = Character.isWhitespace(words.charAt(0)) ? " " : "";
		String trail = Character.isWhitespace(words.charAt(words.length() - 1)) ? " " : "";
		return lead + cleaned + trail;
	}

	// ========== Shapes values show in ==========

	/**
	 * {@code did} as provenance text shows it: DID syntax as it is (W3C DID
	 * Core: letters, digits, {@code . - _ :} and percent-encoding), any other
	 * character percent-encoded as UTF-8, so nothing in a principal's id can
	 * close a label or pose as another part. Null when it is not a DID, or too
	 * long to show.
	 */
	public static String shownDid(AString did) {
		if (did == null) return null;
		String value = did.toString();
		return value.startsWith("did:") ? encoded(value) : null;
	}

	/** {@code value} with every character outside DID syntax percent-encoded, or null when longer than can be shown. */
	public static String encoded(String value) {
		StringBuilder sb = new StringBuilder();
		for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			boolean kept = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
				|| c == '.' || c == '-' || c == '_' || c == ':' || c == '%';
			if (kept) sb.append((char) c);
			else sb.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
			if (sb.length() > MAX_SHOWN_DID) return null;
		}
		return sb.toString();
	}

	/** {@code value} when it is one word of an identifier's, a name's or a path's characters; otherwise null. */
	public static String shownIdentifier(ACell value) {
		return (value instanceof AString s && IDENTIFIER.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	/** {@code value} when it is one word of a platform handle's characters; otherwise null. */
	public static String shownHandle(ACell value) {
		return (value instanceof AString s && HANDLE.matcher(s.toString()).matches()) ? s.toString() : null;
	}

	// ========== Readers ==========

	/**
	 * Whom provenance text is addressed to, which decides how each principal is
	 * named: an agent, whose owner is "your owner" and whose owner's other
	 * agents are its siblings, or a person, whose own agents are "your agent".
	 * Either is "you". Anyone else is named by what it is, with a reference the
	 * reader can use: {@code user <did>}, {@code agent <ownerDID>/g/<id>}, an
	 * anonymous caller, the venue.
	 */
	public static final class Reader {
		private final Engine engine;
		/** The reader. */
		private final AString self;
		/** The account it reads in: an agent's owner, or the person. */
		private final AString account;

		private Reader(Engine engine, AString self, AString account) {
			this.engine = engine;
			this.self = self;
			this.account = account;
		}

		/** Agent {@code agentId} of {@code owner}. */
		public static Reader agent(Engine engine, AString owner, AString agentId) {
			AString self = (owner == null || agentId == null) ? null : Principals.agentDID(owner, agentId);
			return new Reader(engine, self, owner);
		}

		/** The person {@code user}. */
		public static Reader person(Engine engine, AString user) {
			return new Reader(engine, user, user);
		}

		/**
		 * Whoever reads what is made for {@code ctx}: the agent whose execution it
		 * drives, its harness included; otherwise its principal, an agent when
		 * that is one of its account's agents, else the person whose account it is.
		 */
		public static Reader of(Engine engine, RequestContext ctx) {
			if (ctx == null) return person(engine, null);
			RequestContext.ExecutionScope execution = ctx.getExecutionScope();
			if (execution.agentId() != null) return agent(engine, execution.ownerDID(), execution.agentId());
			AString user = ctx.getUserDID();
			AString agentId = Engine.agentIdIn(user, ctx.getAuthorityDID());
			return (agentId != null) ? agent(engine, user, agentId) : person(engine, user);
		}

		private boolean isAgent() {
			return self != null && !self.equals(account);
		}

		/**
		 * {@code principal} as this reader refers to it. A principal a door
		 * authenticated ({@code signedIn}) is named by its DID alone when it is
		 * shaped as an agent's, since a DID's shape says nothing of whose agent
		 * it is; elsewhere the venue recorded the principal itself.
		 */
		String who(AString principal, boolean signedIn) {
			String did = shownDid(principal);
			if (did == null) return UNREADABLE_PRINCIPAL;
			if (engine != null && engine.isPublicPrincipal(principal)) return ANONYMOUS;
			if (principal.equals(self)) return YOU;
			if (principal.equals(account)) return YOUR_OWNER;
			if (engine != null && engine.isVenuePrincipal(principal)) return VENUE;
			AString agentId = Principals.agentIdOf(principal);
			if (agentId == null) return "user " + did;
			if (signedIn) return did;
			String id = encoded(agentId.toString());
			AString sibling = Engine.agentIdIn(account, principal);
			if (sibling != null && id != null) return (isAgent() ? "sibling agent g/" : "your agent g/") + id;
			String owner = shownDid(Principals.userOf(principal));
			return (owner == null || id == null) ? did : "agent " + owner + "/g/" + id;
		}

		/** Whose something is: "your" for the reader's own, otherwise its principal named as {@link #who} names it, possessive. */
		String whose(AString principal) {
			String who = who(principal, false);
			return YOU.equals(who) ? "your" : who + "'s";
		}

		/** {@code path} in {@code owner}'s namespace: the path to a reader in that namespace, otherwise {@code <owner>/<path>}. */
		String ref(AString owner, ACell path) {
			String shown = shownIdentifier(path);
			if (shown == null) return UNREADABLE_REF;
			if (owner == null || owner.equals(account)) return shown;
			String did = shownDid(owner);
			return (did == null) ? UNREADABLE_REF : did + "/" + shown;
		}
	}
}

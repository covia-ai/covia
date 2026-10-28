package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Target-side admission policy for an agent record (covia#447): the owner's
 * standing statement of who, besides the owner, may <em>talk to</em> the agent
 * — submit {@code agent/request} (request, trigger) or {@code agent/message}
 * (message, chat, step) to it — without presenting a delegation.
 *
 * <p>Admission answers only who may ask. An admitted request runs as the agent
 * itself, under its own {@code config.caps}, whoever asked and whichever door
 * (REST, MCP, A2A) the request came through.</p>
 *
 * <p>This is the pure evaluation of a {@code config.accepts} value. Looking the
 * record up, and deciding to consult it at all, is {@link Engine#crossUserAllows}'s
 * business — the single cross-user gate. The policy is one of:</p>
 * <ul>
 *   <li>{@link #OWNER} (or absent) — nobody is admitted; a cross-user caller
 *       needs a presented proof, exactly as before.</li>
 *   <li>{@link #VENUE} — the venue operator: principals whose <em>user</em> is
 *       the venue's own DID, i.e. the venue acting directly and agents it owns.
 *       This is distinct from "everyone hosted on this venue", which is
 *       deliberately not a class: it would usually be too broad, and the
 *       operator must stay clearly distinguishable from every other user.</li>
 *   <li>{@link #ANYONE} — every caller, including an anonymous one (the venue's
 *       public principal). Whether anonymous callers reach the venue at all,
 *       and may invoke the operations that ask, is the operator's
 *       {@code auth.public} policy.</li>
 *   <li>An array — an allowlist matched <b>exactly</b> against the caller's
 *       DID: a user DID admits that user only, {@code <userDID>:g:<agentId>}
 *       admits that one agent only. No prefixes, no families. The
 *       {@code "venue"} keyword may appear as an entry.</li>
 * </ul>
 *
 * <p>Anything else fails closed: a malformed policy admits nobody, and
 * {@link #problem} names the fault so configuration surfaces reject it at
 * author time. Only {@link #ANYONE} admits the venue's public principal.
 * Admission covers talking only: reads ({@code crud/read}) and control or
 * edits ({@code agent/write}) still require a delegation.</p>
 */
public final class Admission {

	/** Nobody besides the owner (the default when {@code accepts} is absent). */
	public static final AString OWNER = Strings.intern("owner");
	/** The venue operator: the venue principal and the agents it owns. */
	public static final AString VENUE = Strings.intern("venue");
	/** Every caller, the anonymous public principal included. */
	public static final AString ANYONE = Strings.intern("anyone");

	private static final AString K_PUBLIC = Strings.intern("public");
	private static final AString K_CAPS = Strings.intern("caps");

	private Admission() {}

	/**
	 * The agent's admission policy: {@code config.accepts}. It reads one older
	 * shape (docs/MIGRATE.md): a public A2A agent with {@code a2a.caps} admitted
	 * anonymous callers, so it reads as {@link #ANYONE}.
	 *
	 * @param config the agent record's config (may be null)
	 * @return the policy to evaluate with {@link #admits}, or null for owner-only
	 */
	public static ACell policy(AMap<AString, ACell> config) {
		if (config == null) return null;
		AMap<AString, ACell> a2a = RT.ensureMap(config.get(Fields.A2A));
		if (a2a != null && a2a.get(K_CAPS) != null && CVMBool.TRUE.equals(a2a.get(K_PUBLIC))) {
			return ANYONE;
		}
		return config.get(Fields.ACCEPTS);
	}

	/**
	 * Whether {@code accepts} admits a named (not anonymous) caller. A policy
	 * with a {@link #problem} admits nobody.
	 *
	 * @param accepts       the agent's admission {@link #policy} (may be null)
	 * @param callerDID     the acting principal's DID (null is never admitted)
	 * @param callerIsVenue whether the caller's user is the venue operator
	 */
	public static boolean admits(ACell accepts, AString callerDID, boolean callerIsVenue) {
		return admits(accepts, callerDID, callerIsVenue, false);
	}

	/**
	 * Whether {@code accepts} admits a caller. A policy with a {@link #problem}
	 * admits nobody, and only {@link #ANYONE} admits the public principal.
	 *
	 * @param accepts        the agent's admission {@link #policy} (may be null)
	 * @param callerDID      the acting principal's DID (null is never admitted)
	 * @param callerIsVenue  whether the caller's user is the venue operator
	 * @param callerIsPublic whether the caller is the venue's public principal
	 */
	public static boolean admits(ACell accepts, AString callerDID, boolean callerIsVenue,
			boolean callerIsPublic) {
		if (accepts == null || callerDID == null) return false;
		if (problem(accepts) != null) return false;
		if (ANYONE.equals(accepts)) return true;
		if (callerIsPublic) return false;
		if (accepts instanceof AString s) {
			return VENUE.equals(s) && callerIsVenue;
		}
		AVector<?> entries = (AVector<?>) accepts;
		long n = entries.count();
		for (long i = 0; i < n; i++) {
			AString entry = (AString) entries.get(i);
			if (VENUE.equals(entry)) {
				if (callerIsVenue) return true;
			} else if (entry.equals(callerDID)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * What is wrong with an {@code accepts} value, or null when it is well-formed:
	 * the string {@code "owner"}, {@code "venue"} or {@code "anyone"}, or an array
	 * whose entries are principal DIDs (or the {@code "venue"} keyword).
	 */
	public static String problem(ACell accepts) {
		if (accepts == null) return null;
		if (accepts instanceof AString s) {
			if (OWNER.equals(s) || VENUE.equals(s) || ANYONE.equals(s)) return null;
			return "must be \"owner\", \"venue\", \"anyone\", or an array of principal DIDs; got \"" + s + "\"";
		}
		if (accepts instanceof AVector<?> entries) {
			long n = entries.count();
			for (long i = 0; i < n; i++) {
				ACell entry = entries.get(i);
				if (entry instanceof AString s && (VENUE.equals(s) || s.toString().startsWith("did:"))) continue;
				return "array entries must be principal DIDs (or \"venue\"); entry " + i + " is "
					+ ((entry instanceof AString s) ? "\"" + s + "\"" : "not a string");
			}
			return null;
		}
		return "must be \"owner\", \"venue\", \"anyone\", or an array of principal DIDs; got "
			+ accepts.getClass().getSimpleName();
	}
}

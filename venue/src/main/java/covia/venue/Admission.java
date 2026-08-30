package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Strings;

/**
 * Target-side admission policy for an agent record (covia#447): the owner's
 * standing statement of who, besides the owner, may <em>talk to</em> the agent
 * — submit {@code agent/request} (request, trigger) or {@code agent/message}
 * (message, chat, step) to it — without presenting a delegation.
 *
 * <p>This is the pure evaluation of a {@code config.accepts} value. Looking the
 * record up, and deciding to consult it at all, is {@link Engine#crossUserAllows}'s
 * business — the single cross-user gate. Admission is deliberately narrow:</p>
 * <ul>
 *   <li>{@link #OWNER} (or absent) — nobody is admitted; a cross-user caller
 *       needs a presented proof, exactly as before.</li>
 *   <li>{@link #VENUE} — the venue operator: principals whose <em>user</em> is
 *       the venue's own DID, i.e. the venue acting directly and agents it owns.
 *       This is distinct from "everyone hosted on this venue", which is
 *       deliberately not a class: it would usually be too broad, and the
 *       operator must stay clearly distinguishable from every other user.</li>
 *   <li>An array — an allowlist matched <b>exactly</b> against the caller's
 *       DID: a user DID admits that user only, {@code <userDID>:g:<agentId>}
 *       admits that one agent only. No prefixes, no families. The
 *       {@code "venue"} keyword may appear as an entry.</li>
 * </ul>
 *
 * <p>Anything else fails closed: a malformed policy admits nobody, and
 * {@link #problem} names the fault so configuration surfaces reject it at
 * author time. The venue's public principal is never admitted — anonymous
 * exposure stays A2A's {@code a2a.public} + {@code a2a.caps}. Admission covers
 * talking only: reads ({@code crud/read}) and control or edits
 * ({@code agent/write}) still require a delegation.</p>
 */
public final class Admission {

	/** Nobody besides the owner (the default when {@code accepts} is absent). */
	public static final AString OWNER = Strings.intern("owner");
	/** The venue operator: the venue principal and the agents it owns. */
	public static final AString VENUE = Strings.intern("venue");

	private Admission() {}

	/**
	 * Whether {@code accepts} admits a caller. A policy with a {@link #problem}
	 * admits nobody.
	 *
	 * @param accepts       the agent record's {@code config.accepts} value (may be null)
	 * @param callerDID     the acting principal's DID (null is never admitted)
	 * @param callerIsVenue whether the caller's user is the venue operator
	 */
	public static boolean admits(ACell accepts, AString callerDID, boolean callerIsVenue) {
		if (accepts == null || callerDID == null) return false;
		if (problem(accepts) != null) return false;
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
	 * the string {@code "owner"} or {@code "venue"}, or an array whose entries are
	 * principal DIDs (or the {@code "venue"} keyword).
	 */
	public static String problem(ACell accepts) {
		if (accepts == null) return null;
		if (accepts instanceof AString s) {
			if (OWNER.equals(s) || VENUE.equals(s)) return null;
			return "must be \"owner\", \"venue\", or an array of principal DIDs; got \"" + s + "\"";
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
		return "must be \"owner\", \"venue\", or an array of principal DIDs; got "
			+ accepts.getClass().getSimpleName();
	}
}

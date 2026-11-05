package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Who made a call and whose namespace it belongs to, as a record names them
 * (COG-8). {@link Fields#CALLER} is the principal that made the call: a user,
 * one of a user's agents ({@code <owner>:g:<agent>}) or a {@code user/sudo}
 * delegate. {@link Fields#OWNER} is the namespace the record belongs to,
 * written only when it differs from the caller. These two methods hold that
 * rule, so every record applies it alike.
 *
 * <p>The owner is never derived from a DID: a delegate's name says nothing
 * about the user it acts for, and anyone may present a DID shaped like an
 * agent's. Whoever writes a record states both.</p>
 */
public final class Attribution {

	private Attribution() {}

	/**
	 * {@code record} naming {@code caller}, and {@code owner} when it differs.
	 *
	 * @param caller the principal that made the call, or null for none
	 * @param owner the namespace the record belongs to, or null for the caller's own
	 */
	public static AMap<AString, ACell> attribute(AMap<AString, ACell> record, AString caller, AString owner) {
		if (caller != null) record = record.assoc(Fields.CALLER, caller);
		if (owner != null && !owner.equals(caller)) record = record.assoc(Fields.OWNER, owner);
		return record;
	}

	/** The principal that made the call {@code record} names, or null when it names none. */
	public static AString caller(AMap<AString, ACell> record) {
		return (record == null) ? null : RT.ensureString(record.get(Fields.CALLER));
	}

	/**
	 * The namespace {@code record} belongs to: its {@link Fields#OWNER}, else
	 * its caller's own. Null when it names neither.
	 */
	public static AString owner(AMap<AString, ACell> record) {
		if (record == null) return null;
		AString owner = RT.ensureString(record.get(Fields.OWNER));
		return (owner != null) ? owner : RT.ensureString(record.get(Fields.CALLER));
	}
}

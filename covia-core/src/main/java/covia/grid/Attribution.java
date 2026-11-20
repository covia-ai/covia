package covia.grid;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * Who made a call, as a record of it names them (COG-8). {@link Fields#CALLER}
 * is the principal that called: a user, one of a user's agents
 * ({@code <owner>:g:<agent>}), or a delegate on work it left before
 * {@code admin/sudo} acted as the user. A record is kept in an account, and
 * leaves out a caller that is that account, since where it is kept says so;
 * whatever reads it against that account reads the missing caller as the
 * account. A response that hands a client one record on its own names the
 * caller in full ({@link #inFull}). These methods hold that rule, so every
 * record applies it alike.
 */
public final class Attribution {

	private Attribution() {}

	/**
	 * {@code record} naming {@code caller}, left out when it is {@code account}.
	 *
	 * @param caller the principal that made the call, or null for none
	 * @param account the account the record is kept in
	 */
	public static AMap<AString, ACell> attribute(AMap<AString, ACell> record, AString caller, AString account) {
		if (caller == null || caller.equals(account)) return record.dissoc(Fields.CALLER);
		return record.assoc(Fields.CALLER, caller);
	}

	/**
	 * The principal that made the call {@code record} names, read against the
	 * account it is kept in: its {@link Fields#CALLER}, else that account.
	 *
	 * @param account the account the record is kept in
	 */
	public static AString caller(AMap<AString, ACell> record, AString account) {
		if (record == null) return null;
		AString caller = RT.ensureString(record.get(Fields.CALLER));
		return (caller != null) ? caller : account;
	}

	/**
	 * {@code record} as a client is handed it on its own, away from the account
	 * it is kept in: naming its caller in full.
	 *
	 * @param account the account the record is kept in
	 */
	public static AMap<AString, ACell> inFull(AMap<AString, ACell> record, AString account) {
		if (record == null || account == null || record.containsKey(Fields.CALLER)) return record;
		return record.assoc(Fields.CALLER, account);
	}
}

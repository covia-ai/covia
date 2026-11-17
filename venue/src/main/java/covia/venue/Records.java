package covia.venue;

import java.util.function.Supplier;

import convex.lattice.ALatticeComponent;
import convex.lattice.cursor.MissingValueException;

/** Transactions on records that may not exist. */
final class Records {

	private Records() {}

	/**
	 * Runs {@code transaction} on {@code record}, giving {@code absent} when the
	 * record does not exist. Convex refuses a transaction on a missing place,
	 * before its callback runs and when a concurrent deletion makes it run again,
	 * so a record that is gone is never written back. A missing value met while
	 * the record still exists is not this, and is thrown on.
	 *
	 * @param record The record's component
	 * @param transaction A transaction on that component
	 * @param absent What a missing record gives
	 * @return What the transaction returned, or {@code absent}
	 */
	static <R> R ifPresent(ALatticeComponent<?> record, Supplier<R> transaction, R absent) {
		try {
			return transaction.get();
		} catch (MissingValueException e) {
			if (record.storage().getOrDefault(null) != null) throw e;
			return absent;
		}
	}
}

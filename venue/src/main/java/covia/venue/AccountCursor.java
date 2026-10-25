package covia.venue;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.AUpdateCursor;

/** A retained user/agent/workspace cursor belongs to exactly one account lifecycle. */
final class AccountCursor extends AUpdateCursor<ACell, ACell> {
	private final ACell generation;

	AccountCursor(AForkableCursor<ACell> base) {
		super(base);
		generation = RT.getIn(base.get(), Users.GENERATION);
	}

	@Override
	protected ACell view(ACell value) {
		// Evaluated inside the underlying atomic update, including CAS retries.
		// Old cursors cannot read or write a newly provisioned account with the same DID.
		Users.requireCurrent(value, generation);
		return value;
	}

	@Override
	protected ACell prepareWrite(ACell value) {
		return value;
	}

}

package covia.venue;

import java.util.Objects;

import convex.core.data.ACell;
import convex.core.lang.RT;
import convex.lattice.cursor.ALatticeCursor;
import convex.lattice.cursor.AUpdateCursor;
import covia.exception.AuthException;
import covia.lattice.Covia;

/** A retained user/agent/workspace cursor belongs to exactly one account lifecycle. */
final class AccountCursor extends AUpdateCursor<ACell, ACell> {
	private final ACell generation;

	AccountCursor(ALatticeCursor<ACell> base) {
		super(base, base.getLattice(), null);
		generation = RT.getIn(base.get(), Users.GENERATION);
	}

	@Override
	protected ACell view(ACell value) {
		// Evaluated inside the underlying atomic update, including CAS retries.
		// Old cursors cannot read or write a newly provisioned account with the same DID.
		if (RT.getIn(value, Covia.K_DELETED) != null
				|| !Objects.equals(generation, RT.getIn(value, Users.GENERATION))) {
			throw new AuthException(Users.ACCOUNT_DELETED);
		}
		return value;
	}

	@Override
	protected ACell prepareWrite(ACell value) {
		return value;
	}

	@Override
	public ACell merge(ACell other) {
		return updateAndGet(current -> lattice.merge(getContext(), current, other));
	}
}

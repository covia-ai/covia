package covia.venue;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.ATransformCursor;
import covia.exception.NotFoundException;
import covia.lattice.Covia;

/**
 * An account's record as a path reaches it ({@link Users#at}). A record that
 * is not live, absent or a deleted account's tombstone, reads as absent and
 * takes no change, so a path never reads venue bookkeeping or writes into a
 * tombstone. Bound before the account exists, it follows the record as it
 * comes into being, in a draft as well; once it has seen a live account, a
 * later account with the same DID is fenced off as {@link AccountCursor} fences it.
 */
final class PathAccountCursor extends ATransformCursor<ACell, ACell> {
	private final AString did;
	private final ACell generation;

	PathAccountCursor(AForkableCursor<ACell> base, AString did) {
		super(base);
		this.did = did;
		this.generation = RT.getIn(base.get(), Users.GENERATION);
	}

	@Override
	protected ACell view(ACell stored) {
		if (stored == null || RT.getIn(stored, Covia.K_DELETED) != null) return null;
		Users.requireCurrent(stored, generation);
		return stored;
	}

	@Override
	protected ACell write(ACell current, ACell value) {
		// Evaluated inside the underlying atomic update, including CAS retries.
		if (current == null) throw new NotFoundException(VenueState.UNKNOWN_USER, did.toString());
		Users.requireCurrent(current, generation);
		return value;
	}
}

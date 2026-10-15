package covia.lattice;

import convex.core.data.ACell;
import convex.lattice.LatticeContext;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.StampedCursor;
import convex.lattice.generic.JSONLattice;

/**
 * An already-authorised workspace base. The owning component supplies its policy
 * and whether this is a user namespace root or a scoped agent/session/job record.
 * Physical cursors remain independent of namespace views and lattice policy.
 */
public record WorkspaceRoot(AForkableCursor<ACell> cursor, LatticeContext context,
		boolean userNamespaces) {

	public AForkableCursor<ACell> child(ACell key) {
		AForkableCursor<ACell> child = cursor.child(key, JSONLattice.INSTANCE);
		if (!userNamespaces || !(Namespace.W.equals(key) || Namespace.O.equals(key) || Namespace.H.equals(key))) {
			return child;
		}
		return StampedCursor.create(child, Covia.WORKSPACE_RECORD, context::currentTimestamp)
				.child(Covia.WORKSPACE_DATA, JSONLattice.INSTANCE);
	}
}

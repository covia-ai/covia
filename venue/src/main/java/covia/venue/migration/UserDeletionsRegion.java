package covia.venue.migration;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.lattice.Covia;
import covia.lattice.Namespace;

/**
 * Venue version 0 to 1: the index of user deletions still to finish moves
 * from the venue user's workspace, {@code w/user-deletions}, to the venue's
 * {@code :user-deletions} region. It is framework state, which no workspace
 * write should reach.
 *
 * <p>The index moves exactly. A venue that already holds an equal copy in the
 * region only loses the workspace copy; a different copy is refused with
 * {@link #CONFLICT}, since there is no rule for merging the two.</p>
 */
public final class UserDeletionsRegion implements MigrationStep {
	public static final String CONFLICT =
		"Pending user deletions differ between w/user-deletions and :user-deletions";

	private static final AString USER_DELETIONS = Strings.intern("user-deletions");

	@Override
	public ACell apply(ACell venue) {
		AString did = RT.ensureString(RT.getIn(venue, Covia.DID));
		if (did == null) return venue;
		ACell[] workspace = {Covia.USER_DATA, did, Namespace.W, Covia.WORKSPACE_DATA};
		AMap<ACell, ACell> data = RT.ensureMap(RT.getIn(venue, workspace));
		ACell legacy = (data == null) ? null : data.get(USER_DELETIONS);
		if (legacy == null) return venue;

		@SuppressWarnings("unchecked")
		AMap<ACell, ACell> record = (AMap<ACell, ACell>) venue;
		if (record.containsKey(Covia.USER_DELETIONS)) {
			if (!legacy.equals(record.get(Covia.USER_DELETIONS))) throw new IllegalStateException(CONFLICT);
		} else {
			record = record.assoc(Covia.USER_DELETIONS, legacy);
		}
		return RT.assocIn(record, data.dissoc(USER_DELETIONS), workspace);
	}
}

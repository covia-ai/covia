package covia.venue.migration;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.Cursors;
import covia.lattice.Covia;
import covia.lattice.WorkspaceRoot;
import covia.venue.VenueState;

/** Venue step 0 → 1 as a function of the venue value. */
class UserDeletionsRegionTest {
	private static final AString DID = Strings.create("did:test:venue");
	private static final ACell[] LEGACY = WorkspaceRoot.parseStringPath("w/user-deletions");
	private static final ACell[] NOTES = WorkspaceRoot.parseStringPath("w/notes");
	private static final ACell PENDING = Maps.of("unloaded", Maps.of("did:test:deleted", CVMLong.ONE));
	private static final MigrationStep STEP = new UserDeletionsRegion();

	/** A version 0 venue value, written through the venue's own accessors. */
	private static ACell legacy() {
		VenueState venue = VenueState.create(AKeyPair.generate());
		venue.cursor().set(Index.<Keyword, ACell>none().assoc(Covia.DID, DID));
		venue.users().ensure(DID).workspaceRoot().write(LEGACY, PENDING);
		venue.users().ensure(DID).workspaceRoot().write(NOTES, Strings.create("kept"));
		return venue.get();
	}

	private static VenueState read(ACell value) {
		return VenueState.create(AKeyPair.generate()).withCursor(Cursors.root(value));
	}

	@Test void theIndexMovesExactlyToItsRegion() {
		ACell migrated = STEP.apply(legacy());
		assertEquals(PENDING, RT.getIn(migrated, Covia.USER_DELETIONS));
		WorkspaceRoot workspace = read(migrated).users().get(DID).workspaceRoot();
		assertNull(workspace.read(LEGACY));
		assertEquals(Strings.create("kept"), workspace.read(NOTES), "the rest of the workspace stays");
		assertSame(migrated, STEP.apply(migrated), "migrating again changes nothing");
	}

	@Test void aVenueWithNothingToMoveComesBackAsTheSameCell() {
		assertNull(STEP.apply(null), "a fresh venue");
		ACell noIdentity = Index.<Keyword, ACell>none().assoc(Covia.VERSION, CVMLong.ZERO);
		assertSame(noIdentity, STEP.apply(noIdentity));
		ACell noIndex = Index.<Keyword, ACell>none().assoc(Covia.DID, DID);
		assertSame(noIndex, STEP.apply(noIndex));
	}

	@Test void anEqualCopyInTheRegionOnlyLosesTheWorkspaceCopy() {
		ACell both = RT.assocIn(legacy(), PENDING, Covia.USER_DELETIONS);
		ACell migrated = STEP.apply(both);
		assertEquals(PENDING, RT.getIn(migrated, Covia.USER_DELETIONS));
		assertNull(read(migrated).users().get(DID).workspaceRoot().read(LEGACY));
	}

	@Test void aDifferentCopyInTheRegionIsRefused() {
		ACell conflicting = RT.assocIn(legacy(), Maps.of("other", Maps.empty()), Covia.USER_DELETIONS);
		assertEquals(UserDeletionsRegion.CONFLICT,
			assertThrows(IllegalStateException.class, () -> STEP.apply(conflicting)).getMessage());
	}
}

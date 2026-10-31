package covia.venue.auth;

import java.util.Map;
import java.util.function.Supplier;

import convex.core.data.AString;
import covia.grid.Venue;

/**
 * Venue identity proof mechanisms supplied by an adapter, independently of
 * its public operation assets, for the operator to list in
 * {@code auth.venueProof}. Names are local to that adapter.
 *
 * <p>A mechanism answers one question: does the venue reached at a target
 * hold a DID? Any listed mechanism suffices, so each must be sound on its
 * own. Declarations must be immutable and stable for the installed
 * instance's lifetime.</p>
 */
public interface VenueProofMethods {

	Map<AString, Method> venueProofMethods();

	interface Method {
		/**
		 * Whether the venue reached at {@code target} holds {@code did}.
		 *
		 * @param target The hop's target: a venue URL or DID
		 * @param did The DID to prove
		 * @param connection Opens an anonymous connection to the target
		 *        through the hop's outbound guard; a mechanism dials the target
		 *        only through it
		 * @return null when proven, else the reason it is not
		 */
		String prove(String target, String did, Supplier<Venue> connection);

		/** Seconds for which a proof may be reused for the same target and DID; 0 for none. */
		default long reuseSeconds() { return 0; }
	}
}

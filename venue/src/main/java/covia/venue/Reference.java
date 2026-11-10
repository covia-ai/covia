package covia.venue;

import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Path;
import covia.grid.HexId;
import covia.lattice.CapabilityChecker;
import covia.lattice.CoviaPath;
import covia.lattice.Namespace;

/**
 * A reference as the venue's components look it up ({@link Agent#lookup},
 * {@link User#lookup}, {@link VenueState#lookup}): the DID it names, if any,
 * and the path after it.
 *
 * @param owner The DID the reference names, or null for a path in its context
 * @param path The path after the DID
 */
record Reference(AString owner, Path path) {

	static Reference of(AString ref) {
		AString owner = Engine.ownerOf(ref);
		String name = CapabilityChecker.canonicalResource(ref.toString(), null);
		return new Reference(owner, CoviaPath.parse((owner == null) ? name : name.substring(owner.toString().length())));
	}

	/** Whether the path starts at {@code namespace}. */
	boolean in(AString namespace) {
		return !path.isEmpty() && namespace.equals(path.get(0));
	}

	/** The asset the path names by its hash, {@code a/<hash>} or, with no DID, a bare hash; otherwise null. */
	Hash asset() {
		if (path.count() == 2 && in(Namespace.A)) return HexId.parseHash(path.get(1));
		return (path.count() == 1 && owner == null) ? HexId.parseHash(path.get(0)) : null;
	}
}

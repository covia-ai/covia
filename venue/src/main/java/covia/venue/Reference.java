package covia.venue;

import convex.core.data.AString;
import convex.core.data.Path;
import covia.lattice.CapabilityChecker;
import covia.lattice.CoviaPath;

/**
 * A reference as the venue's components look it up ({@link Agent#lookup},
 * {@link User#lookup}, {@link VenueState#lookup}): the DID it names, if any,
 * and the path after it, split by the rules of GRID_LATTICE_DESIGN.md §3.2.
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
}

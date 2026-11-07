package covia.lattice;

import java.util.Objects;

import convex.core.data.AString;
import convex.core.data.Path;
import convex.core.data.Strings;
import covia.venue.RequestContext;

/**
 * Who may use the data a component owns. An {@link Owner} has a policy built
 * from its own identity, and the innermost owner a path crosses that has one
 * decides ({@link Owner#decide}), so the rule holds however the path started.
 *
 * <p>A policy reads only the request and the stretch of the path below its
 * owner, never the data, and checks nothing itself: it says what the request
 * needs, and the {@link Access} the request reaches the data through checks
 * that against the request's authority.</p>
 */
@FunctionalInterface
public interface AccessPolicy {

	/**
	 * What {@code ctx} needs to use {@code ability} on the data at {@code path}
	 * below the policy's owner.
	 *
	 * @param path The stretch of the path below the owner, possibly empty
	 */
	Need need(RequestContext ctx, AString ability, Path path);

	/**
	 * What a request needs: nothing when it acts of right; authority over an
	 * address, written as a capability names the data
	 * ({@code <ownerDID>/<path>}); or nothing allows it, with the reason.
	 *
	 * @param address The address the request's authority must cover, or null
	 * @param refusal Why nothing allows the request, or null
	 */
	record Need(AString address, String refusal) {

		/** The request acts of right and needs no grant. */
		public static final Need OF_RIGHT = new Need(null, null);

		/** The request needs authority over {@code address}. */
		public static Need authorityOver(AString address) {
			return new Need(Objects.requireNonNull(address, "address"), null);
		}

		/** Nothing allows the request, for {@code reason}. */
		public static Need refused(String reason) {
			return new Need(null, Objects.requireNonNull(reason, "reason"));
		}
	}

	/**
	 * The address of the data at {@code path} below an owner that {@code base}
	 * names, such as {@code <ownerDID>/g/<agent>}: the base followed by each
	 * segment of the stretch. An empty base gives the bare path, such as
	 * {@code v/ops}, which a capability reads in the request's own namespace.
	 */
	static AString address(String base, Path path) {
		StringBuilder sb = new StringBuilder(base);
		for (long i = 0; i < path.count(); i++) {
			if (sb.length() > 0) sb.append('/');
			sb.append(path.get(i));
		}
		return Strings.create(sb.toString());
	}

	/** The policy of data a principal holds: authority over {@code <did>/<path>}. */
	static AccessPolicy principal(AString did) {
		String base = did.toString();
		return (ctx, ability, path) -> Need.authorityOver(address(base, path));
	}
}

package covia.lattice;

import convex.core.data.AString;
import convex.core.data.Path;
import covia.venue.RequestContext;

/**
 * A component that owns a kind of data and decides who may use it: a user, an
 * agent, a session, a user's jobs and a job. Its {@link AccessPolicy} covers
 * the data below it that no owner nearer the data claims.
 *
 * <p>An owner names the owners below it with one {@link #route routing step},
 * used both to locate a path and to decide who may use it, so the two always
 * agree on which owner a path reaches and how many segments that takes.
 * {@link #decide} walks the steps from an owner a request starts at.</p>
 */
public interface Owner {

	/** Who may use this owner's data, or null when nothing says whose it is, as outside any user. */
	AccessPolicy policy();

	/**
	 * The owner nearer the data that the first segments of {@code path} name,
	 * whether or not it exists, or null when the path stays with this owner.
	 *
	 * @param path The stretch of the caller's path below this owner
	 */
	default Route route(Path path) {
		return null;
	}

	/** Where {@code path}, the stretch of the caller's path below this owner, ends. */
	Position locate(Path path);

	/**
	 * A step from an owner into a child owner.
	 *
	 * @param child The owner the step reaches
	 * @param taken How many segments of the path the step takes
	 */
	record Route(Owner child, int taken) {}

	/**
	 * Who decides on the data at {@code path} below {@code start}: the innermost
	 * owner on the way that has a policy, with the stretch of the path below it.
	 */
	static Ownership decide(Owner start, Path path) {
		Ownership decided = new Ownership(start.policy(), path);
		Owner owner = start;
		Path rest = path;
		for (Route route = owner.route(rest); route != null; route = owner.route(rest)) {
			owner = route.child();
			rest = rest.slice(route.taken());
			if (owner.policy() != null) decided = new Ownership(owner.policy(), rest);
		}
		return decided;
	}

	/**
	 * The policy that decides on some data, and the stretch of the path from
	 * its owner to the data.
	 *
	 * @param policy The owner's policy, or null when no owner on the way has one
	 * @param below The stretch of the path below that owner
	 */
	record Ownership(AccessPolicy policy, Path below) {

		/**
		 * What {@code ctx} needs to use {@code ability} on the data, or at a
		 * level above it: {@code levels} levels up, never above the first segment
		 * below the owner.
		 *
		 * @return The need, or null when no owner has a policy
		 */
		public AccessPolicy.Need need(RequestContext ctx, AString ability, int levels) {
			if (policy == null) return null;
			long keep = Math.max(Math.min(1, below.count()), below.count() - levels);
			return policy.need(ctx, ability, below.slice(0, keep));
		}
	}
}

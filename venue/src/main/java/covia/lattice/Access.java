package covia.lattice;

import java.util.Objects;
import java.util.function.Function;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Path;
import convex.lattice.NoSuchPathException;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.venue.RequestContext;

/**
 * A request's access to the data at a path: the component the path resolves
 * to, reached only after the owner's {@link AccessPolicy} allows the request.
 * The owner is the innermost one the path crosses that has a policy
 * ({@link Owner#decide}). Each read or change first asks the policy what the
 * request needs and checks it: nothing when the request acts of right,
 * otherwise the request's authority over the address the owner names, through
 * the venue's shared check.
 *
 * <p>A path that names no place is no failure to make an access: it answers
 * {@link #isValid} with false, and reading or changing it is refused with
 * {@link ACoviaComponent#NO_SUCH_PATH}, before any policy is asked, since
 * validity follows only the path's shape and says nothing of the data.</p>
 *
 * <p>The covia adapter makes these for the paths requests name
 * ({@link covia.adapter.CoviaAdapter#resolve}). An access hands out the data
 * only through its checks, so code acting for a request reaches the data only
 * through it; venue code acting for itself uses the components directly.</p>
 */
public final class Access {

	/**
	 * The venue's check of a request's authority over an address, as a
	 * capability names it: the owner as of right within its own scope, anyone
	 * else with a grant. With no address the request acts of right, and only
	 * its standing is checked, such as an account that still exists.
	 */
	@FunctionalInterface
	public interface Authority {
		/** @throws AuthException when the request's authority does not cover {@code ability} on {@code address} */
		void require(RequestContext ctx, AString address, AString ability);
	}

	/** Refusal for data no owner has said how to use; names the path. A venue bug if seen. */
	public static final String NO_OWNER = "No owner decides who may use %s";

	private final RequestContext ctx;
	/** The component the path resolves to, or null when it names no place. */
	private final ACoviaComponent<?> destination;
	/** Why the path names no place, or null when it names one. */
	private final String invalid;
	private final Owner.Ownership ownership;
	private final Authority authority;

	/**
	 * @param start The component the path starts at
	 * @param path The caller's path below {@code start}
	 * @param ownership Who decides on the data there
	 */
	public Access(RequestContext ctx, ACoviaComponent<?> start, Path path, Owner.Ownership ownership, Authority authority) {
		this.ctx = ctx;
		this.ownership = Objects.requireNonNull(ownership, "ownership");
		this.authority = Objects.requireNonNull(authority, "authority");
		ACoviaComponent<?> found = null;
		String reason = null;
		try {
			found = start.resolve(path);
		} catch (NoSuchPathException e) {
			reason = ACoviaComponent.noSuchPath(e);
		}
		this.destination = found;
		this.invalid = reason;
	}

	/** Whether the path names a place that could hold data, whether or not anything is there; checks nothing. */
	public boolean isValid() {
		return destination != null;
	}

	/** Why the path names no place, saying where it stops naming one; null when it names a place. */
	public String reasonInvalid() {
		return invalid;
	}

	/** The value here as callers see it, or null when absent, once the request may read it. */
	public ACell read() {
		require(Capability.CRUD_READ);
		return valid().read();
	}

	/** Whether a value is here now, once the request may read it; a stored null counts. */
	public boolean exists() {
		require(Capability.CRUD_READ);
		return valid().contains(CoviaPath.EMPTY);
	}

	/** The workspace entry here, to change it with {@code ability}, once the request may ({@link Entry#of}). */
	public Entry entry(AString ability) {
		require(ability);
		return Entry.of(valid());
	}

	/**
	 * The component the path resolves to, to act on it with {@code ability}
	 * through its parent's own operations, once the request may; null when the
	 * path is invalid.
	 */
	public ACoviaComponent<?> component(AString ability) {
		require(ability);
		return destination;
	}

	private ACoviaComponent<?> valid() {
		if (destination == null) throw new InvalidInputException(invalid);
		return destination;
	}

	/**
	 * Changes the entry here with {@code ability} in one transaction, first
	 * creating the levels above it that hold nothing. Creating a key changes the
	 * level that holds it, so a change that creates levels also needs
	 * {@code crud/write} at the nearest level that exists; it is checked before
	 * the transaction, and again inside it only when more levels are missing by
	 * then.
	 *
	 * @param change The change, made to the entry in the transaction, returning its result
	 * @return The change's result in the run that committed, and whether a level
	 *         above the changed value was created
	 */
	public <R> Changed<R> changeCreatingLevels(AString ability, Function<? super Entry, ? extends R> change) {
		Entry live = entry(ability);
		int checked = live.missingLevels();
		if (checked > 0) require(Capability.CRUD_WRITE, checked);
		return live.transact(draft -> {
			int missing = draft.missingLevels();
			if (missing > checked) require(Capability.CRUD_WRITE, missing);
			boolean created = draft.createLevels() > 0;
			return new Changed<R>(change.apply(draft), created);
		});
	}

	/**
	 * What {@link #changeCreatingLevels} did.
	 *
	 * @param result The change's own result
	 * @param levelsCreated Whether a level above the changed value was created
	 */
	public record Changed<R>(R result, boolean levelsCreated) {}

	/**
	 * Checks that the request may use {@code ability} here. An access checks
	 * each ability once: it is made for one request's use of one path.
	 */
	public void require(AString ability) {
		if (ability.equals(allowed)) return;
		require(ability, 0);
		allowed = ability;
	}

	/** The ability last found allowed here, so a read or change that follows its check is not checked again. */
	private AString allowed;

	/**
	 * Checks that the request may use {@code ability} {@code levels} levels
	 * above here, never above the owner's namespace. A path that names no place
	 * needs no check: reading or changing it is refused as no such path, which
	 * says nothing of the data, since validity follows only the path's shape.
	 */
	private void require(AString ability, int levels) {
		if (destination == null) return;
		AccessPolicy.Need need = ownership.need(ctx, ability, levels);
		if (need == null) throw new IllegalStateException(String.format(NO_OWNER, CoviaPath.address(ownership.below())));
		if (need.refusal() != null) throw new AuthException(need.refusal());
		authority.require(ctx, need.address(), ability);
	}
}

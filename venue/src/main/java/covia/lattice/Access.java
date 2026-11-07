package covia.lattice;

import java.util.Objects;
import java.util.function.Consumer;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AString;
import covia.exception.AuthException;
import covia.venue.RequestContext;

/**
 * A request's access to the data at a path: the {@link Position} the path ends
 * at, reached only after the owner's {@link AccessPolicy} allows the request.
 * Each read or change first asks the policy what the request needs and checks
 * it: nothing when the request acts of right, otherwise the request's authority
 * over the address the owner names, through the venue's shared check.
 *
 * <p>The covia adapter makes these for the paths requests name
 * ({@link covia.adapter.CoviaAdapter#resolve}). An access never hands out its
 * position, so code acting for a request reaches the data only through it;
 * venue code acting for itself uses the components directly.</p>
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
	private final Position position;
	private final Authority authority;

	public Access(RequestContext ctx, Position position, Authority authority) {
		this.ctx = ctx;
		this.position = Objects.requireNonNull(position, "position");
		this.authority = Objects.requireNonNull(authority, "authority");
	}

	/** Whether the path names a place that could hold data, whether or not anything is there; checks nothing. */
	public boolean isValid() {
		return position.isValid();
	}

	/** The path as the caller wrote it. */
	public CoviaPath path() {
		return position.path();
	}

	/** The value here as callers see it, or null when absent, once the request may read it. */
	public ACell read() {
		require(Capability.CRUD_READ);
		return position.read();
	}

	/** Whether a value is here now, once the request may read it. */
	public boolean exists() {
		require(Capability.CRUD_READ);
		return position.exists();
	}

	/** The workspace entry here, to change it with {@code ability}, once the request may. */
	public Entry entry(AString ability) {
		require(ability);
		return position.entry();
	}

	/**
	 * Changes the entry here with {@code ability} in one transaction, first
	 * creating the levels above it that hold nothing. Creating a key changes the
	 * level that holds it, so a change that creates levels also needs
	 * {@code crud/write} at the nearest level that exists; it is checked before
	 * the transaction, and again inside it only when more levels are missing by
	 * then.
	 *
	 * @param change The change, made to the entry in the transaction
	 * @return Whether a level above the changed value was created
	 */
	public boolean changeCreatingLevels(AString ability, Consumer<Entry> change) {
		Entry live = entry(ability);
		int checked = live.missingLevels();
		if (checked > 0) require(Capability.CRUD_WRITE, checked);
		boolean[] created = {false};
		live.transact(draft -> {
			int missing = draft.missingLevels();
			if (missing > checked) require(Capability.CRUD_WRITE, missing);
			created[0] = draft.createLevels() > 0;
			change.accept(draft);
		});
		return created[0];
	}

	/** Checks that the request may use {@code ability} here. */
	public void require(AString ability) {
		require(ability, 0);
	}

	/** Checks that the request may use {@code ability} {@code levels} levels above here, never above the owner's namespace. */
	private void require(AString ability, int levels) {
		AccessPolicy.Need need = position.need(ctx, ability, levels);
		if (need == null) {
			if (!position.isValid()) return;   // reading or changing it is refused as no such path
			throw new IllegalStateException(String.format(NO_OWNER, position.path()));
		}
		if (need.refusal() != null) throw new AuthException(need.refusal());
		authority.require(ctx, need.address(), ability);
	}
}

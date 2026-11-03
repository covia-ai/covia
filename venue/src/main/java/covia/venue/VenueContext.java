package covia.venue;

import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.AString;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;
import covia.exception.AuthException;

/**
 * Policy shared by every component in the Covia tree. One instance serves a
 * venue: the application builds it and each component passes it unchanged to
 * the children it creates, so nothing copies or rebuilds it per component.
 *
 * <p>Components take their policy from here rather than from a
 * {@code LatticeContext}, which only the application's root merge uses. The
 * engine installs its access check when the venue starts; until then every
 * access is refused.</p>
 */
public final class VenueContext {

	/** Refusal while no engine has installed an access check. */
	public static final String NO_ACCESS_POLICY = "No access policy is installed for this venue";

	/** Decides whether a principal may use an ability on a resource. */
	@FunctionalInterface
	public interface Access {
		/**
		 * Returns when the principal may act, else throws the caller-facing refusal.
		 *
		 * @param ctx The request acting
		 * @param resource The resource, as {@code Engine.requireLocalAccess} takes it
		 * @param ability The ability required
		 */
		void require(RequestContext ctx, AString resource, AString ability);
	}

	private static final Access REFUSE = (ctx, resource, ability) -> {
		throw new AuthException(NO_ACCESS_POLICY);
	};

	/** Policy with the system clock, for components outside a hosted application. */
	public static final VenueContext SYSTEM = create(Utils::getCurrentTimestampCell);

	private final Supplier<CVMLong> clock;
	private final Access access;

	private VenueContext(Supplier<CVMLong> clock, Access access) {
		this.clock = clock;
		this.access = access;
	}

	/**
	 * Creates policy with the given write clock, refusing all access.
	 *
	 * @param clock Supplies the timestamp of each write; read once per write
	 * @return New policy
	 */
	public static VenueContext create(Supplier<CVMLong> clock) {
		return new VenueContext(Objects.requireNonNull(clock, "clock"), REFUSE);
	}

	/** This policy with the given access check in place of its own. */
	public VenueContext withAccess(Access access) {
		return new VenueContext(clock, Objects.requireNonNull(access, "access"));
	}

	/** The write clock: the timestamp for a stamp or record written now. */
	public CVMLong currentTimestamp() {
		return clock.get();
	}

	// TODO: reconsider approach. Nothing calls this since covia writes check
	// authority themselves; settle how access checks reach components as the
	// component refactor continues (#617).
	/** Returns when the principal may use the ability on the resource, else throws its refusal. */
	public void requireAccess(RequestContext ctx, AString resource, AString ability) {
		access.require(ctx, resource, ability);
	}
}

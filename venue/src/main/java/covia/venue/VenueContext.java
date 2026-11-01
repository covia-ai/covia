package covia.venue;

import java.util.Objects;
import java.util.function.Supplier;

import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * Policy shared by every component in the Covia tree. One instance serves a
 * venue: the application builds it and each component passes it unchanged to
 * the children it creates, so nothing copies or rebuilds it per component.
 *
 * <p>Components take their policy from here rather than from a
 * {@code LatticeContext}, which only the application's root merge uses.</p>
 */
public final class VenueContext {

	/** Policy with the system clock, for components outside a hosted application. */
	public static final VenueContext SYSTEM = create(Utils::getCurrentTimestampCell);

	private final Supplier<CVMLong> clock;

	private VenueContext(Supplier<CVMLong> clock) {
		this.clock = clock;
	}

	/**
	 * Creates policy with the given write clock.
	 *
	 * @param clock Supplies the timestamp of each write; read once per write
	 * @return New policy
	 */
	public static VenueContext create(Supplier<CVMLong> clock) {
		return new VenueContext(Objects.requireNonNull(clock, "clock"));
	}

	/** The write clock: the timestamp for a stamp or record written now. */
	public CVMLong currentTimestamp() {
		return clock.get();
	}
}

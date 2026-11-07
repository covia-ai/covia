package covia.venue;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.core.util.Utils;

/**
 * Policy shared by every component in the Covia tree: the write clock and the
 * venue's signer. One instance serves a venue: whoever builds the venue's state
 * gives it this policy, and each component passes it unchanged to the children
 * it creates, so nothing copies or rebuilds it per component.
 *
 * <p>It holds no access check: a component that owns data says who may use it
 * ({@link covia.lattice.AccessPolicy}) and checks nothing itself, and a request
 * reaches the data through an {@link covia.lattice.Access}, which checks.</p>
 */
public final class VenueContext {

	/** Policy with the system clock and no signer, for components outside a hosted application. */
	public static final VenueContext SYSTEM = create(Utils::getCurrentTimestampCell);

	private final Supplier<CVMLong> clock;
	private final Function<ACell, SignedData<ACell>> signer;

	private VenueContext(Supplier<CVMLong> clock, Function<ACell, SignedData<ACell>> signer) {
		this.clock = clock;
		this.signer = signer;
	}

	/**
	 * Creates policy with the given write clock and no signer.
	 *
	 * @param clock Supplies the timestamp of each write; read once per write
	 * @return New policy
	 */
	public static VenueContext create(Supplier<CVMLong> clock) {
		return new VenueContext(Objects.requireNonNull(clock, "clock"), null);
	}

	/** This policy's clock, signing as the given venue key. */
	public VenueContext withSigner(AKeyPair keyPair) {
		Objects.requireNonNull(keyPair, "keyPair");
		return new VenueContext(clock, keyPair::signData);
	}

	/** The write clock: the timestamp for a stamp or record written now. */
	public CVMLong currentTimestamp() {
		return clock.get();
	}

	/**
	 * Signs a value as the venue.
	 *
	 * @return The signed value, or null when this policy has no signer
	 */
	public SignedData<ACell> sign(ACell value) {
		return (signer == null) ? null : signer.apply(value);
	}
}

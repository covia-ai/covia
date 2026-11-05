package covia.lattice;

import convex.core.data.AString;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;

/**
 * Resolves the {@code v/} virtual namespace to the venue's shared
 * definitions, {@link covia.venue.VenueState#globals()}.
 *
 * <p>Per OPERATIONS.md §3, {@code /v/} is the venue's public bulletin
 * board: {@code v/<rest>} is {@code <venue-DID>/w/global/<rest>}. The venue
 * is treated as a user (it has its own DID and keypair), and the
 * {@code global} area of its workspace holds venue-provided data.</p>
 *
 * <p>This is a <b>venue-scoped</b> virtual namespace — unlike {@code n/}
 * (caller's running agent) and {@code t/} (caller's running job), which
 * navigate within the caller's own user data, it reaches the same area
 * whoever the caller is.</p>
 *
 * <p><b>Access policy.</b> Reads are universally allowed; writes require a
 * caller DID equal to the venue's own DID. The {@link #canWrite} override
 * enforces this; reads bypass it because they don't go through a write
 * check.</p>
 */
public class VenueGlobalsResolver implements NamespaceResolver {

	/**
	 * Reference to the adapter, kept so {@link #canWrite(RequestContext)}
	 * can reach the engine's venue DID. {@link #resolve} also gets the
	 * adapter as a parameter, but {@code canWrite} does not.
	 */
	private final CoviaAdapter coviaAdapter;

	public VenueGlobalsResolver(CoviaAdapter coviaAdapter) {
		this.coviaAdapter = coviaAdapter;
	}

	@Override
	public Position resolve(RequestContext ctx, CoviaAdapter adapter, CoviaPath path) {
		Workspace globals = adapter.engine.getVenueState().globals();
		return globals == null ? null : globals.resolve(path.rest());
	}

	/**
	 * The {@code /v/} namespace is statically writable (the resolver supports
	 * writes), but the runtime check in {@link #canWrite(RequestContext)}
	 * restricts who may actually perform them.
	 */
	@Override
	public boolean isWritable() {
		return true;
	}

	/**
	 * Per-caller write authorisation. Only the venue identity may write to
	 * {@code /v/} — the caller's DID must match the venue's own DID.
	 * Engine startup code that needs to write here uses
	 * {@link covia.venue.Engine#venueContext()}, which has the venue's DID
	 * as its caller; operator writes work the same way (JWT signed by the
	 * venue keypair).
	 */
	@Override
	public boolean canWrite(RequestContext ctx) {
		if (ctx == null) return false;
		AString callerDID = ctx.getCallerDID();
		if (callerDID == null) return false;
		AString venueDID = coviaAdapter.engine.getDIDString();
		return callerDID.equals(venueDID);
	}
}

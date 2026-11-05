package covia.lattice;

import convex.core.data.AString;
import covia.adapter.CoviaAdapter;
import covia.venue.RequestContext;
import covia.venue.User;

/**
 * Resolves the {@code v/} virtual namespace, an alias: {@code v/<rest>} is the
 * venue user's path {@code w/global/<rest>}, that is
 * {@code <venue-DID>/w/global/<rest>}.
 *
 * <p>Per OPERATIONS.md §3, {@code /v/} is the venue's public bulletin
 * board. The venue is treated as a user (it has its own DID and keypair), and
 * {@code w/global} in its workspace holds venue-provided data. The alias
 * resolves by the ordinary walk from the venue user, whoever the caller is,
 * so a refusal names the path it stands for.</p>
 *
 * <p><b>Access policy.</b> Reads are universally allowed; writes require a
 * caller DID equal to the venue's own DID. The {@link #canWrite} override
 * enforces this; reads bypass it because they don't go through a write
 * check.</p>
 */
public class VenueGlobalsResolver implements NamespaceResolver {

	/** The venue user's path that {@code v/} stands for. */
	public static final CoviaPath TARGET = CoviaPath.parse("w/global");

	/**
	 * The venue user's path a {@code v/} path stands for.
	 *
	 * @param path A path whose first segment is {@code v}
	 * @return {@code w/global} followed by the rest of the path
	 */
	public static CoviaPath target(CoviaPath path) {
		return TARGET.append(path.rest());
	}

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
		AString venueDID = adapter.engine.getDIDString();
		User venue = venueDID == null ? null : adapter.engine.getVenueState().users().get(venueDID);
		return venue == null ? null : venue.resolve(target(path));
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

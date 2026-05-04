package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.lattice.cursor.ALatticeCursor;
import covia.venue.RequestContext;

/**
 * Resolves the {@code v/} virtual namespace to the venue user's
 * {@code w/global/} sub-tree.
 *
 * <p>Per OPERATIONS.md §3, {@code /v/} is the venue's public bulletin
 * board: {@code v/<rest>} resolves to {@code <venue-DID>/w/global/<rest>}.
 * The venue is treated as a user (it has its own DID and keypair) so its
 * {@code /w/global/} sub-tree is the host for venue-provided data.</p>
 *
 * <p>This is the first <b>venue-scoped</b> virtual namespace — unlike
 * {@code n/} (caller's running agent) and {@code t/} (caller's running
 * job), which navigate within the caller's own user data, this resolver
 * navigates to a fixed user (the venue) regardless of the caller. The
 * resolved cursor is the venue user's cursor, retrieved via
 * {@link CoviaAdapter#ensureUserCursor(AString)}.</p>
 *
 * <p><b>Access policy.</b> Reads are universally allowed; writes require
 * the venue identity (either {@link RequestContext#isInternal()} or a
 * caller DID equal to the venue's own DID). The {@link #canWrite}
 * override enforces this; reads bypass it because they don't go through
 * a write check.</p>
 */
class VenueGlobalsResolver implements NamespaceResolver {

	private static final AString K_W = Strings.intern("w");
	private static final AString K_GLOBAL = Strings.intern("global");

	/**
	 * Reference to the adapter, kept so {@link #canWrite(RequestContext)}
	 * can reach the engine's venue DID. {@link #resolve} also gets the
	 * adapter as a parameter, but {@code canWrite} does not.
	 */
	private final CoviaAdapter coviaAdapter;

	VenueGlobalsResolver(CoviaAdapter coviaAdapter) {
		this.coviaAdapter = coviaAdapter;
	}

	@Override
	public ResolvedNamespace resolve(RequestContext ctx, CoviaAdapter adapter, ACell[] keys) {
		// keys[0] is "v" (the prefix). Rewrite to <venue-DID>/w/global/<rest>
		// by returning the venue user's cursor with rewritten keys
		// [w, global, ...rest].
		AString venueDID = adapter.engine.getDIDString();
		if (venueDID == null) return null;

		ALatticeCursor<ACell> venueCursor = adapter.ensureUserCursor(venueDID);

		ACell[] rewritten = new ACell[keys.length + 1];
		rewritten[0] = K_W;
		rewritten[1] = K_GLOBAL;
		System.arraycopy(keys, 1, rewritten, 2, keys.length - 1);
		return new ResolvedNamespace(venueCursor, rewritten);
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

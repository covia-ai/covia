package covia.venue;

import java.io.IOException;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.LatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.LatticeHost;
import covia.lattice.Covia;

/**
 * Hosted application component for the complete Covia lattice.
 *
 * <p>This is the root-level composition point between a generic lattice host
 * ({@link LatticeHost}) and Covia's path-specific domain components. It owns
 * neither the host nor its store lifecycle.</p>
 *
 * <p>Each running venue uses one authoritative connected state and atomic
 * transactions. Whole-venue LWW selects recovery/replication snapshots; it cannot
 * reconcile the state or external effects of independently running forks.</p>
 */
public final class CoviaApplication
		extends LatticeApplication<Index<Keyword, ACell>> {

	private final boolean ephemeral;
	private final LatticeHost<Index<Keyword, ACell>> host;

	private CoviaApplication(LatticeHost<Index<Keyword, ACell>> host,
			boolean ephemeral) {
		// Use the live-cursor constructor. The host-based constructor creates a
		// private working copy, which would split Engine's authoritative state.
		super(Covia.ROOT, host.cursor());
		this.host = host;
		this.ephemeral = ephemeral;
		host.addApplication(this);
	}

	/** Connects Covia to an existing local or NodeServer-hosted root. */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host) {
		return new CoviaApplication(host, false);
	}

	/** Creates an in-memory application for tests and embedded ephemeral use. */
	public static CoviaApplication create(AKeyPair keyPair) {
		if (keyPair == null) {
			throw new IllegalArgumentException("Venue key pair must not be null");
		}
		LatticeHost<Index<Keyword, ACell>> root =
			LatticeHost.create(Index.none(), new MemoryStore());
		var application = new CoviaApplication(root, true);
		application.setContext(LatticeContext.create(null, keyPair));
		return application;
	}

	/**
	 * Opens Covia from a caller-owned store and installs a live signing/clock
	 * policy for the supplied venue key.
	 */
	public static CoviaApplication open(AStore store, AKeyPair keyPair)
			throws IOException {
		if (keyPair == null) {
			throw new IllegalArgumentException("Venue key pair must not be null");
		}
		LatticeHost<Index<Keyword, ACell>> root =
			LatticeHost.open(Index.none(), store);
		var application = connect(root);
		application.setContext(LatticeContext.create(null, keyPair));
		return application;
	}

	/** Returns the component for one owner-signed venue record. */
	public VenueState venue(AccountKey ownerKey) {
		return VenueState.connect(this, ownerKey);
	}

	/** Returns the caller-owned host store for internal adapter integration. */
	AStore hostStore() {
		return host.store();
	}

	/** Host-owned persistence and durability boundary. */
	public LatticeHost<Index<Keyword, ACell>> host() {
		return host;
	}

	/** Whether this application was created as an in-memory ephemeral host. */
	boolean isEphemeral() {
		return ephemeral;
	}
}

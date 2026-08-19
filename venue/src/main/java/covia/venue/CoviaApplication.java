package covia.venue;

import java.io.IOException;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AccountKey;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeContext;
import convex.lattice.RootComponent;
import covia.lattice.Covia;

/**
 * Hosted application component for the complete Covia lattice.
 *
 * <p>This is the root-level composition point between a generic lattice host
 * ({@link RootComponent}) and Covia's path-specific domain components. It owns
 * neither the host nor its store lifecycle.</p>
 */
public final class CoviaApplication
		extends ALatticeApplication<Index<Keyword, ACell>> {

	private final boolean ephemeral;

	private CoviaApplication(RootComponent<Index<Keyword, ACell>> host,
			boolean ephemeral) {
		super(host);
		this.ephemeral = ephemeral;
	}

	/** Connects Covia to an existing local or NodeServer-hosted root. */
	public static CoviaApplication connect(
			RootComponent<Index<Keyword, ACell>> host) {
		return new CoviaApplication(host, false);
	}

	/** Creates an in-memory application for tests and embedded ephemeral use. */
	public static CoviaApplication create(AKeyPair keyPair) {
		if (keyPair == null) {
			throw new IllegalArgumentException("Venue key pair must not be null");
		}
		RootComponent<Index<Keyword, ACell>> root =
			RootComponent.create(Covia.ROOT, new MemoryStore());
		root.cursor().setContext(LatticeContext.create(null, keyPair));
		return new CoviaApplication(root, true);
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
		RootComponent<Index<Keyword, ACell>> root =
			RootComponent.open(Covia.ROOT, store);
		root.cursor().setContext(LatticeContext.create(null, keyPair));
		return connect(root);
	}

	/** Returns the component for one owner-signed venue record. */
	public VenueState venue(AccountKey ownerKey) {
		return VenueState.connect(this, ownerKey);
	}

	/** Whether this application was created as an in-memory ephemeral host. */
	boolean isEphemeral() {
		return ephemeral;
	}
}

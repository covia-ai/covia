package covia.venue;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.AccountKey;
import convex.core.data.Hash;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.SignedData;
import convex.core.store.AStore;
import convex.core.store.MemoryStore;
import convex.dlfs.DLFSRegion;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.OwnedMap;
import convex.lattice.Regions;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.generic.CASLattice;
import covia.lattice.Covia;

/**
 * Covia's application on a lattice host: the venue state of COG-004's grid.
 *
 * <p>Its regions are {@code :grid}, holding {@code :venues} (each venue's record,
 * signed by that venue's key) and {@code :meta} (shared content-addressed
 * metadata). Incoming state and path deliveries go to those components, and
 * other regions are left alone both ways. The application is built over the
 * cursor its service gives it and assumes nothing about where that is; the
 * factories here place it at the host root, as COG-004 does.</p>
 *
 * <p>DLFS drives live in a shared region at the host root, {@code :dlfs}, which
 * the service registers with the host and hands to the application
 * ({@link #dlfs()}); Covia never resolves it from its own position.</p>
 *
 * <p>Each running venue uses one authoritative connected state and atomic
 * transactions. Whole-venue LWW selects recovery/replication snapshots; it cannot
 * reconcile the state or external effects of independently running forks.</p>
 */
public final class CoviaApplication
		extends ALatticeApplication<Index<Keyword, ACell>> {

	/** A venue's record is signed by the venue's own key. */
	private static final BiPredicate<ACell, AccountKey> KEY_OWNER = (owner, key) -> key.equals(owner);

	private final Regions regions;
	private final OwnedMap<ACell, ACell> venues;
	private final DLFSRegion dlfs;
	/** The host the factory opened or connected to; it owns storage and durability. */
	private final LatticeHost<Index<Keyword, ACell>> host;
	private final boolean ephemeral;
	/** The clock, and signer if any, of venue components built through this application. */
	private final VenueContext venueContext;

	private CoviaApplication(AForkableCursor<Index<Keyword, ACell>> cursor, VenueContext venueContext,
			DLFSRegion dlfs, LatticeHost<Index<Keyword, ACell>> host, boolean ephemeral) {
		super(cursor);
		this.venueContext = Objects.requireNonNull(venueContext, "venueContext");
		this.dlfs = Objects.requireNonNull(dlfs, "dlfs");
		this.host = host;
		this.ephemeral = ephemeral;
		this.regions = new Regions(cursor);
		this.venues = venueRecords(regions);
	}

	/** Registers :grid and its regions under {@code regions}, returning the venue records. */
	private static OwnedMap<ACell, ACell> venueRecords(Regions regions) {
		Regions grid = regions.create(Covia.GRID,
			(AForkableCursor<Index<Keyword, ACell>> cursor) -> new Regions(cursor));
		OwnedMap<ACell, ACell> venues = grid.create(Covia.VENUES,
			(AForkableCursor<AHashMap<ACell, SignedData<ACell>>> cursor) -> new OwnedMap<>(cursor, Covia.VENUE, KEY_OWNER));
		grid.create(Covia.META,
			(AForkableCursor<Index<Hash, ACell>> cursor) -> new LatticeComponent<>(CASLattice.<Hash, ACell>create(), cursor));
		return venues;
	}

	/**
	 * The venue records under a Covia container, for reading a snapshot or
	 * building a standalone venue: the same components, registered nowhere.
	 */
	static OwnedMap<ACell, ACell> venueRecords(AForkableCursor<Index<Keyword, ACell>> cursor) {
		return venueRecords(new Regions(cursor));
	}

	@Override
	public Index<Keyword, ACell> merge(Index<Keyword, ACell> incoming) {
		return regions.merge(incoming);
	}

	@Override
	public Index<Keyword, ACell> mergeAt(ACell value, ACell... path) {
		return regions.mergeAt(value, path);
	}

	@Override
	protected void syncChildren() {
		regions.sync();
	}

	/**
	 * Connects Covia to the root of an existing local or NodeServer-hosted host,
	 * registering its application and a DLFS region at {@code :dlfs}. Venue
	 * components it builds sign nothing until the engine adds its key.
	 */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host) {
		return connect(host, VenueContext.SYSTEM);
	}

	/** As {@link #connect(LatticeHost)}, its venue components using the given policy. */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host, VenueContext context) {
		return install(host, context, false);
	}

	/** Creates an in-memory application for tests and embedded ephemeral use. */
	public static CoviaApplication create(AKeyPair keyPair) {
		if (keyPair == null) {
			throw new IllegalArgumentException("Venue key pair must not be null");
		}
		return create(VenueContext.SYSTEM.withSigner(keyPair));
	}

	/** Creates an in-memory application whose venue components use the given policy. */
	public static CoviaApplication create(VenueContext context) {
		return install(LatticeHost.create(Index.none(), new MemoryStore()), context, true);
	}

	/**
	 * Opens Covia from a caller-owned store, its venue components signing with
	 * the supplied venue key.
	 */
	public static CoviaApplication open(AStore store, AKeyPair keyPair)
			throws IOException {
		if (keyPair == null) {
			throw new IllegalArgumentException("Venue key pair must not be null");
		}
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.open(Index.none(), store);
		return install(host, VenueContext.SYSTEM.withSigner(keyPair), false);
	}

	/**
	 * Acts as the service for a host at whose root Covia lives: registers the
	 * shared DLFS region and the application, and hands the region over.
	 */
	private static CoviaApplication install(LatticeHost<Index<Keyword, ACell>> host,
			VenueContext context, boolean ephemeral) {
		Regions shared = new Regions(host.cursor());
		DLFSRegion dlfs = shared.create(Covia.DLFS,
			(AForkableCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> cursor)
				-> DLFSRegion.connect(cursor, host.store()));
		// Live over the host's root, so Engine's state and the host's are one.
		CoviaApplication application = new CoviaApplication(host.cursor(), context, dlfs, host, ephemeral);
		host.addApplication(application);
		host.addApplication(shared);
		return application;
	}

	/** Returns the component for one owner-signed venue record. */
	public VenueState venue(AccountKey ownerKey) {
		return VenueState.connect(this, ownerKey, venueContext);
	}

	/** Each venue's record, signed by that venue's key. */
	OwnedMap<ACell, ACell> venues() {
		return venues;
	}

	/**
	 * The shared DLFS region: incoming drives are delivered to it, and a user's
	 * drives are authored over its cursor with that user's key.
	 */
	public DLFSRegion dlfs() {
		return dlfs;
	}

	/** The policy shared by this application's venue components. */
	public VenueContext venueContext() {
		return venueContext;
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

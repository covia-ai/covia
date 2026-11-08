package covia.venue;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AHashMap;
import convex.core.data.AMap;
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
import covia.exception.ConfigurationException;
import covia.exception.ConflictException;
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
 * <p>The application runs the venues mounted in it ({@link #mount}). Each is
 * its key pair, its one working copy ({@link #venue}) and its engine
 * ({@link #engine}), and signs with the application's policy and its own key.
 * Edits to a copy are unsigned; {@link #sync()} signs each venue's record once
 * and then publishes the regions. Unmounting a venue signs its copy and closes
 * its engine; its records stay. Whole-venue LWW selects recovery/replication
 * snapshots; it cannot reconcile the state or external effects of independently
 * running forks.</p>
 */
public final class CoviaApplication
		extends ALatticeApplication<Index<Keyword, ACell>> implements AutoCloseable {

	/** A venue's record is signed by the venue's own key. */
	private static final BiPredicate<ACell, AccountKey> KEY_OWNER = (owner, key) -> key.equals(owner);

	/** Refusal of a venue that is not mounted here; the argument is the venue's key. */
	public static final String NOT_MOUNTED = "This application does not run venue %s: mount it with that venue's key pair";

	/** Refusal to mount a venue already mounted here; the argument is the venue's key. */
	public static final String ALREADY_MOUNTED = "Venue %s is already mounted in this application";

	/** Refusal of an application policy that signs: each venue brings its own key when it mounts. */
	public static final String POLICY_SIGNS = "An application's policy signs as no venue: mount each venue with its key pair";

	/** Builds a mounted venue's engine; tests in this package substitute their own. */
	@FunctionalInterface
	interface EngineFactory {
		Engine create(Config config, CoviaApplication application, AKeyPair keyPair);
	}

	/** A mounted venue: its working copy and its engine. */
	private record Mounted(VenueState state, Engine engine) {}

	private final Regions regions;
	private final OwnedMap<ACell, ACell> venues;
	/** Each venue mounted here, by its key. */
	private final ConcurrentHashMap<AccountKey, Mounted> mounted = new ConcurrentHashMap<>();
	private final DLFSRegion dlfs;
	/** The host the factory opened or connected to; it owns storage and durability. */
	private final LatticeHost<Index<Keyword, ACell>> host;
	/** The application's policy, its clock: each mounted venue adds its own signer. */
	private final VenueContext venueContext;

	private CoviaApplication(AForkableCursor<Index<Keyword, ACell>> cursor, VenueContext venueContext,
			DLFSRegion dlfs, LatticeHost<Index<Keyword, ACell>> host) {
		super(cursor);
		this.venueContext = Objects.requireNonNull(venueContext, "venueContext");
		this.dlfs = Objects.requireNonNull(dlfs, "dlfs");
		this.host = host;
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

	/** Each venue signs its working copy into its record, then the records publish. */
	@Override
	protected void syncChildren() {
		for (Mounted venue : mounted.values()) venue.state().sync();
		regions.sync();
	}

	/**
	 * Connects Covia to the root of an existing local or NodeServer-hosted host,
	 * registering its application and a DLFS region at {@code :dlfs}, with the
	 * system clock and no venue mounted.
	 */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host) {
		return install(host, VenueContext.SYSTEM);
	}

	/**
	 * As {@link #connect(LatticeHost)}, mounting the venue whose key pair is
	 * given with the default configuration.
	 */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host, AKeyPair keyPair) {
		return connect(host).mountDefault(keyPair);
	}

	/**
	 * As {@link #connect(LatticeHost)}, with the given application policy.
	 *
	 * @throws ConfigurationException if the policy signs ({@link #POLICY_SIGNS})
	 */
	public static CoviaApplication connect(
			LatticeHost<Index<Keyword, ACell>> host, VenueContext policy) {
		return install(host, requireUnsigned(policy));
	}

	/** Creates an in-memory application with the system clock and no venue mounted. */
	public static CoviaApplication create() {
		return create(VenueContext.SYSTEM);
	}

	/**
	 * Creates an in-memory application, for tests and embedded ephemeral use,
	 * mounting the venue whose key pair is given with the default configuration.
	 */
	public static CoviaApplication create(AKeyPair keyPair) {
		return create().mountDefault(keyPair);
	}

	/**
	 * Creates an in-memory application with the given application policy and no
	 * venue mounted.
	 *
	 * @throws ConfigurationException if the policy signs ({@link #POLICY_SIGNS})
	 */
	public static CoviaApplication create(VenueContext policy) {
		return install(LatticeHost.create(Index.none(), new MemoryStore()), requireUnsigned(policy));
	}

	/**
	 * Opens Covia from a caller-owned store, with the system clock and no venue
	 * mounted.
	 */
	public static CoviaApplication open(AStore store) throws IOException {
		LatticeHost<Index<Keyword, ACell>> host = LatticeHost.open(Index.none(), store);
		return install(host, VenueContext.SYSTEM);
	}

	/**
	 * As {@link #open(AStore)}, mounting the venue whose key pair is given with
	 * the default configuration.
	 */
	public static CoviaApplication open(AStore store, AKeyPair keyPair)
			throws IOException {
		return open(store).mountDefault(keyPair);
	}

	private static VenueContext requireUnsigned(VenueContext policy) {
		Objects.requireNonNull(policy, "policy");
		if (policy.hasSigner()) throw new ConfigurationException(POLICY_SIGNS);
		return policy;
	}

	/** Mounts a venue with the default configuration, for the factories that take its key pair. */
	private CoviaApplication mountDefault(AKeyPair keyPair) {
		mount(keyPair, new Config(null));
		return this;
	}

	/**
	 * Acts as the service for a host at whose root Covia lives: registers the
	 * shared DLFS region and the application, and hands the region over.
	 */
	private static CoviaApplication install(LatticeHost<Index<Keyword, ACell>> host,
			VenueContext context) {
		Regions shared = new Regions(host.cursor());
		DLFSRegion dlfs = shared.create(Covia.DLFS,
			(AForkableCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> cursor)
				-> DLFSRegion.connect(cursor, host.store()));
		// Live over the host's root, so Engine's state and the host's are one.
		CoviaApplication application = new CoviaApplication(host.cursor(), context, dlfs, host);
		host.addApplication(application);
		host.addApplication(shared);
		return application;
	}

	/**
	 * Mounts a venue: the application takes its key pair, holds its working copy
	 * at once, and builds its engine, inert until launched or started. The venue
	 * signs with this application's policy and its own key.
	 *
	 * @param keyPair The venue's key pair
	 * @param config The venue's configuration
	 * @return The venue's engine
	 * @throws ConflictException if the venue is already mounted ({@link #ALREADY_MOUNTED})
	 */
	public Engine mount(AKeyPair keyPair, Config config) {
		return mount(keyPair, config, Engine::new);
	}

	/** As {@link #mount(AKeyPair, Config)}, with the configuration's JSON form. */
	public Engine mount(AKeyPair keyPair, AMap<AString, ACell> config) {
		return mount(keyPair, new Config(config));
	}

	/** As {@link #mount(AKeyPair, Config)}, building the engine with {@code factory}. */
	synchronized Engine mount(AKeyPair keyPair, Config config, EngineFactory factory) {
		Objects.requireNonNull(keyPair, "keyPair");
		Objects.requireNonNull(config, "config");
		AccountKey key = keyPair.getAccountKey();
		if (mounted.containsKey(key)) throw new ConflictException(String.format(ALREADY_MOUNTED, key));
		VenueState state = VenueState.draft(venues, key, venueContext.withSigner(keyPair));
		Engine engine = factory.create(config, this, keyPair);
		mounted.put(key, new Mounted(state, engine));
		return engine;
	}

	/**
	 * Unmounts a venue: closes its engine, which runs its final flush, signs its
	 * working copy into its record and forgets both. Its records stay. Closing
	 * the engine directly unmounts it the same way.
	 *
	 * @throws ConfigurationException if the venue is not mounted ({@link #NOT_MOUNTED})
	 */
	public void unmount(AccountKey venueKey) {
		engine(venueKey).close();
	}

	/**
	 * Unmounts every venue, leaving the host and its store open: whoever
	 * opened the host closes it after this.
	 */
	@Override
	public void close() {
		RuntimeException failure = null;
		for (Mounted venue : mounted.values()) {
			try {
				venue.engine().close();
			} catch (RuntimeException e) {
				if (failure == null) failure = e; else failure.addSuppressed(e);
			}
		}
		if (failure != null) throw failure;
	}

	/**
	 * Called by an engine as its close ends: signs its venue's working copy and
	 * forgets the venue. A copy that fails to sign stays mounted, edits and all.
	 */
	void unmounted(Engine engine) {
		AccountKey key = engine.getAccountKey();
		Mounted venue = mounted.get(key);
		if (venue == null || venue.engine() != engine) return;
		venue.state().sync();
		mounted.remove(key, venue);
	}

	/**
	 * Returns a mounted venue's one working copy, the same component on every
	 * call. Edits to it are unsigned and visible at once to every holder;
	 * {@link #sync()} signs them into the venue's record.
	 *
	 * @throws ConfigurationException if the venue is not mounted ({@link #NOT_MOUNTED})
	 */
	public VenueState venue(AccountKey venueKey) {
		return mounted(venueKey).state();
	}

	/**
	 * Returns a mounted venue's engine.
	 *
	 * @throws ConfigurationException if the venue is not mounted ({@link #NOT_MOUNTED})
	 */
	public Engine engine(AccountKey venueKey) {
		return mounted(venueKey).engine();
	}

	private Mounted mounted(AccountKey venueKey) {
		if (venueKey == null) {
			throw new IllegalArgumentException("Venue key must not be null");
		}
		Mounted venue = mounted.get(venueKey);
		if (venue == null) throw new ConfigurationException(String.format(NOT_MOUNTED, venueKey));
		return venue;
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

	/** The application's policy, its clock, which every mounted venue shares; it has no signer. */
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
}

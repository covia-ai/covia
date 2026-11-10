package covia.venue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import covia.grid.Job;
import covia.lattice.Covia;

/**
 * Covia's application on a lattice host: the venue state of COG-004's grid.
 *
 * <p>The service's main class opens the host and builds and registers this in
 * one expression, {@code host.addApplication(new CoviaApplication(host, clock))}.
 * It sits at the host root, as COG-004 does, and owns neither the host nor the
 * store: whoever opened them closes them.</p>
 *
 * <p>Its regions are {@code :grid}, holding {@code :venues} (each venue's record,
 * signed by that venue's key) and {@code :meta} (shared content-addressed
 * metadata), and {@code :dlfs}, the DLFS drives ({@link #dlfs()}). Incoming
 * state and path deliveries go to those components, and other regions are left
 * alone both ways.</p>
 *
 * <p>The application runs the venues mounted in it ({@link #mount}). Each is
 * its key pair, its one working copy ({@link #venue}) and its engine
 * ({@link #engine}), and signs with the application's policy and its own key.
 * The application's {@link VenueClock} is every venue's time, and wakes the
 * application when the earliest scheduled event across them falls due. On a
 * {@link DrivenClock} nothing wakes it: whoever moves the clock calls
 * {@link #update()} to start what is due.
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

	private static final Logger log = LoggerFactory.getLogger(CoviaApplication.class);

	/** Builds a mounted venue's engine; tests in this package substitute their own. */
	@FunctionalInterface
	interface EngineFactory {
		Engine create(Config config, CoviaApplication application, AKeyPair keyPair);
	}

	/** A mounted venue: its working copy, its engine and its place in mount order. */
	private record Mounted(VenueState state, Engine engine, long order) {}

	private final Regions regions;
	private final OwnedMap<ACell, ACell> venues;
	/** Each venue mounted here, by its key. */
	private final ConcurrentHashMap<AccountKey, Mounted> mounted = new ConcurrentHashMap<>();
	/** The order the next venue mounts in. Guarded by this. */
	private long mounts = 0;
	/** Wakes the application through its clock when something falls due. */
	private final AutoUpdate timer;
	private final DLFSRegion dlfs;
	/** The host this application was built on; it owns storage and durability. */
	private final LatticeHost<Index<Keyword, ACell>> host;
	/** The application's policy, its clock: each mounted venue adds its own signer. */
	private final VenueContext venueContext;

	/**
	 * Builds the application on a host, on the system clock. Register it with
	 * {@link LatticeHost#addApplication}.
	 *
	 * @param host The host, already opened; its root holds the application
	 */
	public CoviaApplication(LatticeHost<Index<Keyword, ACell>> host) {
		this(host, VenueClock.SYSTEM);
	}

	/**
	 * Builds the application on a host, on the given clock. Register it with
	 * {@link LatticeHost#addApplication}. Nothing runs until a venue starts.
	 *
	 * @param host The host, already opened; its root holds the application
	 * @param clock The time of every venue mounted here
	 */
	public CoviaApplication(LatticeHost<Index<Keyword, ACell>> host, VenueClock clock) {
		super(host.cursor());
		Objects.requireNonNull(clock, "clock");
		this.host = host;
		this.venueContext = VenueContext.create(clock);
		this.regions = new Regions(host.cursor());
		this.venues = venueRecords(regions);
		this.dlfs = regions.create(Covia.DLFS,
			(AForkableCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> cursor)
				-> DLFSRegion.connect(cursor, host.store(), null, clock));
		this.timer = new AutoUpdate(this, clock);
		timer.failure().thenAccept(e -> log.error("Venue scheduler: the application's update timer failed", e));
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
	 * Publishes the application through its host, then has each venue's copy read
	 * back what the host persisted, so the copy does not go on holding those cells
	 * in memory. Whoever runs the host's sync runs this in place of {@link #sync()}.
	 *
	 * <p>Interim (covia#678): a copy takes up the host's persisted value only when
	 * it has no changes since its last sync, so this syncs each copy again straight
	 * after the host persists. A write landing in between misses that cycle. The
	 * issue says which Convex changes let callers go back to {@code sync()} and
	 * {@code host().sync()} and this be deleted.</p>
	 */
	public void publish() {
		sync();
		host.sync();
		for (Mounted venue : mounted.values()) venue.state().sync();
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
		VenueState state = VenueState.draft(venues, key, config.venueDID(key), venueContext.withSigner(keyPair));
		Engine engine = factory.create(config, this, keyPair);
		mounted.put(key, new Mounted(state, engine, mounts++));
		return engine;
	}

	/**
	 * Steps the application to its clock's time: reads the clock once and
	 * updates each mounted venue in mount order, starting the scheduled events
	 * due by then. The future completes when each of those has started, that is
	 * when its operation's invocation has returned, and gives their Jobs, venue
	 * by venue in mount order and within a venue in the order it claimed them.
	 * How each resolves is its own: wait on its Job. A failure to start is
	 * logged and does not fail the future; an event whose Job could not be
	 * created has none in the list. A step at an unchanged time still starts
	 * what is due, such as an event added since with a past time. A venue not
	 * yet started, or closing, takes no part.
	 *
	 * <p>The application's clock wakes it to call this whenever a venue has
	 * something due. Whoever moves a {@link DrivenClock} calls it after each
	 * step instead.</p>
	 */
	public CompletableFuture<List<Job>> update() {
		long now = venueContext.currentTimestamp().longValue();
		List<Mounted> order = new ArrayList<>(mounted.values());
		order.sort(Comparator.comparingLong(Mounted::order));
		List<CompletableFuture<List<Job>>> steps = new ArrayList<>(order.size());
		for (Mounted venue : order) {
			try {
				steps.add(venue.engine().update(now));
			} catch (RuntimeException e) {
				steps.add(CompletableFuture.failedFuture(e));
			}
		}
		return CompletableFuture.allOf(steps.toArray(new CompletableFuture<?>[0])).thenApply(all -> {
			List<Job> started = new ArrayList<>();
			for (CompletableFuture<List<Job>> step : steps) started.addAll(step.join());
			return List.copyOf(started);
		});
	}

	/** The update timer. */
	AutoUpdate timer() {
		return timer;
	}

	/**
	 * The earliest time any mounted venue next needs an update, or
	 * {@link Long#MAX_VALUE} for none. Takes each venue's schedule lock in
	 * turn, never two at once.
	 */
	long nextDue() {
		long due = Long.MAX_VALUE;
		for (Mounted venue : mounted.values()) due = Math.min(due, venue.engine().nextDue());
		return due;
	}

	/**
	 * Called by a venue, holding none of its locks, when its next event is
	 * due at {@code time}: the timer asks the clock to wake it by then.
	 */
	void dueAt(long time) {
		timer.dueAt(time);
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
	 * Stops the update timer and unmounts every venue, leaving the host and its
	 * store open: whoever opened the host closes it after this.
	 */
	@Override
	public void close() {
		timer.close();
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
	 * The DLFS region: incoming drives are delivered to it, and a user's drives
	 * are authored over its cursor with that user's key.
	 */
	public DLFSRegion dlfs() {
		return dlfs;
	}

	/** The application's policy, its clock, which every mounted venue shares; it has no signer. */
	public VenueContext venueContext() {
		return venueContext;
	}

	/** Returns the host's store for internal adapter integration. */
	AStore hostStore() {
		return host.store();
	}

	/** Host-owned persistence and durability boundary. */
	public LatticeHost<Index<Keyword, ACell>> host() {
		return host;
	}
}

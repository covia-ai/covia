package covia.venue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
import convex.core.data.prim.CVMLong;
import convex.core.store.AStore;
import convex.core.util.Utils;
import convex.dlfs.DLFSDrives;
import convex.dlfs.DLFSRegion;
import convex.lattice.ALatticeApplication;
import convex.lattice.LatticeComponent;
import convex.lattice.LatticeHost;
import convex.lattice.OwnedMap;
import convex.lattice.Regions;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Transactions;
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
 * and then publishes the regions. {@link #transact} changes mounted venues and
 * DLFS drives in one update of the root. Unmounting a venue signs its copy and closes
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

	/** Refusal of a transaction started inside another transaction's callback on the same thread. */
	public static final String NESTED_TRANSACTION =
		"A transaction cannot start inside another transaction's callback: make the change through that callback's draft";

	/** Refusal of a callback that changed a venue's working copy rather than the draft; the argument is the venue's key. */
	public static final String CHANGED_OUTSIDE_DRAFT =
		"Venue %s changed outside the transaction's draft while its callback ran: make every change through the draft";

	/** Use of a draft after its transaction has finished. */
	public static final String DRAFT_FINISHED = "This transaction has finished: its draft can no longer be used";

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
	/** Set on the thread running a transaction's callback, to refuse a nested one. */
	private final ThreadLocal<Boolean> transacting = new ThreadLocal<>();

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
		this.dlfs = dlfsRegion(regions, host.store(), clock);
		this.timer = new AutoUpdate(this, clock);
		timer.failure().thenAccept(e -> log.error("Venue scheduler: the application's update timer failed", e));
	}

	/**
	 * Registers :dlfs under {@code regions}. The region holds no signer: a user's
	 * drives are authored over its cursor with that user's key.
	 */
	private static DLFSRegion dlfsRegion(Regions regions, AStore store, Supplier<CVMLong> clock) {
		return regions.create(Covia.DLFS,
			(AForkableCursor<AHashMap<ACell, SignedData<AHashMap<AString, AVector<ACell>>>>> cursor)
				-> DLFSRegion.connect(cursor, store, null, clock));
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
	 * Publishes the application through its host, then persists each venue's copy
	 * through the host, so the copy reaches what the host saved through soft
	 * references and holds strongly none of what it has persisted, a write made
	 * during the save included. Whoever runs the host's sync runs this in place of
	 * {@link #sync()}.
	 *
	 * @throws UncheckedIOException if persisting a copy fails; the host has
	 *         saved the root by then
	 */
	public void publish() {
		sync();
		host.sync();
		for (Mounted venue : mounted.values()) {
			try {
				venue.state().persist();
			} catch (IOException e) {
				throw new UncheckedIOException("Persisting a venue's copy failed", e);
			}
		}
	}

	/**
	 * Changes the application's state in one transaction: every change the
	 * callback makes through its {@link Draft}, to any mounted venue and to any
	 * DLFS drive, reaches the host root in one update, or none of it does.
	 *
	 * <p>The callback runs once, while every other write to the application
	 * waits: keep it short. Each mounted venue's working copy is claimed, in
	 * mount order, and synced first, so the draft starts from everything the
	 * venue holds; after the commit each copy syncs again and takes the result,
	 * so it stays its record's only writer. The callback uses only the draft, does
	 * no external I/O and does not wait for other threads. Its exception
	 * propagates and nothing applies.</p>
	 *
	 * <p>A commit is in the host's memory: the host's next sync saves it, and its
	 * flush makes it durable ({@link Engine#flush()}).</p>
	 *
	 * @param <R> The callback's result type
	 * @param work The changes, made through the draft
	 * @return The callback's result, once committed
	 * @throws IllegalStateException if called inside another transaction's callback
	 *         ({@link #NESTED_TRANSACTION}), or if the callback changed a venue's
	 *         working copy rather than the draft ({@link #CHANGED_OUTSIDE_DRAFT})
	 */
	public <R> R transact(Function<? super Draft, ? extends R> work) {
		List<Mounted> order = new ArrayList<>(mounted.values());
		order.sort(Comparator.comparingLong(Mounted::order));
		return transact(order, work);
	}

	/**
	 * As {@link #transact}, with no result.
	 *
	 * @param work The changes, made through the draft
	 */
	public void execute(Consumer<? super Draft> work) {
		Objects.requireNonNull(work, "work");
		transact(draft -> {
			work.accept(draft);
			return null;
		});
	}

	/**
	 * As {@link #transact}, claiming one mounted venue only: the draft reaches no
	 * other venue, and their writes do not wait for the venue syncs.
	 *
	 * @throws ConfigurationException if the venue is not mounted ({@link #NOT_MOUNTED})
	 */
	<R> R transact(AccountKey venueKey, Function<? super Draft, ? extends R> work) {
		return transact(List.of(mounted(venueKey)), work);
	}

	private <R> R transact(List<Mounted> venues, Function<? super Draft, ? extends R> work) {
		Objects.requireNonNull(work, "work");
		if (transacting.get() != null) throw new IllegalStateException(NESTED_TRANSACTION);
		transacting.set(Boolean.TRUE);
		try {
			return claimed(venues, 0, work);
		} finally {
			transacting.remove();
		}
	}

	/**
	 * Takes the claim of each venue's working copy from {@code next} on, then
	 * runs the transaction holding them all. A copy's claim comes before the
	 * root's, the order the copy's own sync takes them in.
	 */
	private <R> R claimed(List<Mounted> venues, int next, Function<? super Draft, ? extends R> work) {
		if (next < venues.size()) {
			return venues.get(next).state().exclusive().transact(held -> claimed(venues, next + 1, work));
		}
		Map<AccountKey, ACell> synced = new LinkedHashMap<>();
		for (Mounted venue : venues) {
			venue.state().sync();
			synced.put(venue.state().getOwnerKey(), venue.state().storage().getOrDefault(null));
		}
		R result = Transactions.transactExclusively(storage(), root -> {
			Draft draft = new Draft(this, root, venues);
			try {
				R r = work.apply(draft);
				draft.signVenues();
				for (Mounted venue : venues) {
					AccountKey key = venue.state().getOwnerKey();
					if (!Utils.equals(synced.get(key), venue.state().storage().getOrDefault(null))) {
						throw new IllegalStateException(String.format(CHANGED_OUTSIDE_DRAFT, key));
					}
				}
				return r;
			} finally {
				draft.finish();
			}
		});
		for (Mounted venue : venues) venue.state().sync();
		return result;
	}

	/**
	 * The application's state inside one transaction ({@link CoviaApplication#transact}):
	 * the venues it claimed and the DLFS drives, over the transaction's private
	 * draft of the host root. Nothing reaches the application until the callback
	 * returns and the transaction commits. Use it only inside the callback.
	 */
	public static final class Draft {
		private final CoviaApplication application;
		private final AForkableCursor<Index<Keyword, ACell>> root;
		/** The claimed venues' policies, each with its venue's signer. */
		private final Map<AccountKey, VenueContext> claimed = new LinkedHashMap<>();
		/** Each venue's state in this draft, opened on first use. */
		private final Map<AccountKey, VenueState> opened = new LinkedHashMap<>();
		private final DLFSRegion dlfs;
		private boolean finished = false;

		private Draft(CoviaApplication application, AForkableCursor<Index<Keyword, ACell>> root, List<Mounted> venues) {
			this.application = application;
			this.root = root;
			for (Mounted venue : venues) claimed.put(venue.state().getOwnerKey(), venue.state().getContext());
			this.dlfs = dlfsRegion(new Regions(root), application.host.store(), application.venueContext::currentTimestamp);
		}

		/**
		 * A claimed venue's state in this transaction, with the venue's own domain
		 * operations. It signs once with the venue's key as the transaction commits.
		 *
		 * @param venueKey The venue's key
		 * @return The venue's state, the same component on every call
		 * @throws ConfigurationException if the venue is not mounted ({@link #NOT_MOUNTED})
		 */
		public VenueState venue(AccountKey venueKey) {
			requireOpen();
			VenueContext context = claimed.get(venueKey);
			if (context == null) throw new ConfigurationException(String.format(NOT_MOUNTED, venueKey));
			return opened.computeIfAbsent(venueKey, key -> VenueState.fromRoot(root, key, context::sign, context));
		}

		/**
		 * An owner's DLFS drives in this transaction, authored with the owner's key.
		 *
		 * @param owner The drives' owner, whose key signs them
		 * @return The owner's drives
		 */
		public DLFSDrives drives(AKeyPair owner) {
			requireOpen();
			return DLFSRegion.connect(dlfs.cursor(), application.host.store(), owner::signData,
				application.venueContext::currentTimestamp).drives(owner.getAccountKey());
		}

		/** Signs each venue changed in this draft into the draft, once. */
		private void signVenues() {
			for (VenueState venue : opened.values()) venue.sync();
		}

		private void finish() {
			finished = true;
		}

		private void requireOpen() {
			if (finished) throw new IllegalStateException(DRAFT_FINISHED);
		}
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
		VenueState state = VenueState.draft(venues, key, venueContext.withSigner(keyPair), host::persist,
			fork -> forked(key, fork));
		Engine engine = factory.create(config, this, keyPair);
		mounted.put(key, new Mounted(state, engine, mounts++));
		return engine;
	}

	/**
	 * A mounted venue's copy published over another writer's version of its
	 * record ({@link VenueState.Fork}). The venue cannot tell what else writes
	 * its record, so it is logged at ERROR and the venue stops
	 * ({@link Engine#stopFatally()}) for its operator to find out.
	 */
	private void forked(AccountKey key, VenueState.Fork fork) {
		String message = String.format(VenueState.FORKED, key, fork.replacedStamp(), fork.replaced(), fork.stamp());
		log.error(fork.replacedNewer() ? message + VenueState.REPLACED_NEWER : message);
		Mounted venue = mounted.get(key);
		if (venue != null) venue.engine().stopFatally();
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
	 * The earliest queued scheduled timestamp across started, non-closing
	 * mounted venues, in epoch milliseconds on this application's clock, or
	 * {@code null} when none is queued. Includes scheduled operations, agent
	 * wakes and HITL timeouts, even while dispatch capacity is full. Overdue
	 * events retain their scheduled timestamp; claimed events are excluded,
	 * while a recurring event's next queued occurrence is included.
	 *
	 * <p>This only reads the schedules: it neither moves the clock nor starts
	 * work. It does not wait for running Jobs or establish that agents are idle.
	 * Each venue is read separately, so concurrent changes can alter the answer
	 * and there is no atomic snapshot across venues. Re-query after each step.
	 * See {@code EMBEDDING.md}, Time.</p>
	 *
	 * @return The next queued timestamp, or {@code null} for none
	 */
	public CVMLong nextDue() {
		CVMLong due = null;
		for (Mounted venue : mounted.values()) {
			CVMLong candidate = venue.engine().gridScheduler().nextDue();
			if (candidate != null && (due == null || candidate.compareTo(due) < 0)) due = candidate;
		}
		return due;
	}

	/** The timer's next wake-up, excluding venues with no dispatch capacity. */
	long nextUpdateTime() {
		long due = Long.MAX_VALUE;
		for (Mounted venue : mounted.values()) due = Math.min(due, venue.engine().nextUpdateTime());
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

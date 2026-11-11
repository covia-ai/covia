package covia.adapter.messaging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.messaging.MessagingBot.Managed;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.exception.NotSupportedException;
import covia.exception.UnavailableException;
import covia.grid.Principals;
import covia.venue.AdapterState;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.migration.MigrationStep;

/**
 * Shared registration, ownership and lifecycle for messaging adapters.
 * Provider subclasses retain configuration parsing, credentials, admission,
 * transports and operation schemas. Installation is inert; {@link #start()}
 * activates workers only after the engine is ready, and {@link #stop()}, when
 * the adapter is disabled or replaced, stops every bot until {@link #start()}
 * starts them again from configuration and the bot registry.
 */
public abstract class AMessagingAdapter<S extends MessagingBotSpec, R extends MessagingBot<S>>
		extends AAdapter implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(AMessagingAdapter.class);
	protected static final AString K_BOTS = Strings.intern("bots");
	private static final AString K_USER = Strings.intern("user");
	/** On a created bot's record: the principal that created it, when not its user (an agent of that user). */
	private static final AString K_CALLER = Fields.CALLER;
	/** The steps of the state every messaging adapter keeps ({@link BotCaller}). */
	private static final List<MigrationStep> STATE_STEPS = List.of(new BotCaller());
	private static final AString K_TOKEN = Strings.intern("token");
	private static final AString K_DELETED = Strings.intern("deleted");

	public static final String AUTH_REQUIRED = " requires an authenticated caller";
	public static final String NAME_REQUIRED = "name is required";
	public static final String SETTINGS_REQUIRED = "create expects an object of bot settings";
	public static final String IMPLICIT_USER = "user is implicit for a created bot — it acts as you";
	public static final String SECRET_REFERENCE_REQUIRED = "token must be an s/NAME secret reference";
	public static final String CLOSED = "Messaging adapter is closed";
	public static final String UNKNOWN_BOT = "Unknown %s bot: %s";
	public static final String NO_BOT = "No %s bot is configured for %s";
	public static final String AMBIGUOUS_BOT = "Several %s bots are available; specify 'bot'";
	public static final String DUPLICATE_BOT = "You already have a %s bot named '%s'";
	public static final String CONFIG_BOT = "Bot '%s' is declared in venue config; remove it there";
	public static final String MISSING_BOT = "You have no %s bot named '%s'";
	public static final String FOREIGN_PRINCIPAL = "a bot's principal must be its user or one of that user's agents, not %s";
	public static final String SEPARATE_AUTHORITY_CREATOR = "a bot acts as its creator, so a request acting with "
		+ "another principal's authority cannot create one";
	/** Creating or deleting a bot while its adapter's bots are stopped; the provider follows. */
	public static final String NOT_RUNNING = "The %s adapter is not running";

	private volatile Map<String, S> specs = Map.of();
	private final Map<String, R> runners = new LinkedHashMap<>();
	/** Created bots' principals, by runtime key, where not the bot's user. */
	private final Map<String, AString> principals = new java.util.concurrent.ConcurrentHashMap<>();
	private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, getName() + "-retry");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean closed;
	/** Whether bots run: set by {@link #start()}, cleared by {@link #stop()}; guarded by this. */
	private boolean running;
	/** Retry cadence for missing credentials or temporarily unavailable providers. */
	public volatile long retryMillis = 30_000;

	protected abstract S parseBot(String name, ACell settings, boolean strict);

	/** Every messaging adapter's state migrates alike: its steps are those of the shared bot registry. */
	@Override
	public List<MigrationStep> stateSteps() {
		return STATE_STEPS;
	}
	protected abstract R newBot(S spec, Managed managed);
	/** Whether this runner still uses the current provider-level configuration. */
	protected abstract boolean configurationMatches(R runner);
	protected String providerName() {
		String name = getName();
		return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1);
	}

	/** Called only after the subclass has validated all proposed settings. */
	protected final synchronized void configureBots(Map<String, S> wanted) {
		if (closed) throw new IllegalStateException(CLOSED);
		specs = Map.copyOf(wanted);
		// Stopped bots take the new settings when start() brings them back.
		if (running) reconcile();
	}

	@Override
	public synchronized void start() {
		if (closed) return;
		running = true;
		reconcile();
		rearmRuntimeBots();
	}

	/**
	 * Stops every bot, closing its poll or connection and keeping its settings
	 * and records, until {@link #start()} starts them again; no bot starts in
	 * between, by configuration, re-arming or {@code create}.
	 */
	@Override
	public synchronized void stop() {
		running = false;
		stopRunners();
	}

	private void stopRunners() {
		try {
			for (R runner : runners.values()) {
				try { runner.stop(); }
				catch (RuntimeException e) { log.warn("{}: failed to stop bot '{}'", getName(), runner.spec().name(), e); }
			}
		} finally {
			runners.clear();
		}
	}

	@Override
	public void deleteUserState(AString did, AdapterState state) {
		state.delete(state.userPath(did, ""));
		// Persisted ownership also covers configurations removed since the bot ran.
		AMap<AString, ACell> configured = RT.ensureMap(state.read("config"));
		if (configured != null) for (var entry : configured.entrySet()) {
			if (did.equals(RT.getIn(entry.getValue(), K_USER))) state.delete("config/" + entry.getKey());
		}
		// Older config records predate the ownership field.
		for (S spec : specs.values()) {
			if (did.toString().equals(spec.userRef())) state.delete("config/" + spec.name());
		}
	}

	@Override
	public synchronized void onUserDeleted(AString did, AMap<AString, ACell> previousUser) {
		RuntimeException failure = null;
		for (var iterator = runners.entrySet().iterator(); iterator.hasNext();) {
			R runner = iterator.next().getValue();
			if (!did.equals(runner.spec().userDID(engine))) continue;
			try {
				runner.stop();
				iterator.remove();
			} catch (RuntimeException e) {
				// Keep failed runners reachable for the next deletion attempt.
				if (failure == null) failure = e; else failure.addSuppressed(e);
			}
		}
		if (failure != null) throw failure;
	}

	private void reconcile() {
		List<String> stale = new ArrayList<>();
		for (var entry : runners.entrySet()) {
			R runner = entry.getValue();
			if (runner.managed() != Managed.CONFIG) continue;
			if (!runner.spec().equals(specs.get(entry.getKey())) || !configurationMatches(runner)) {
				stale.add(entry.getKey());
			}
		}
		for (String name : stale) runners.remove(name).stop();
		for (S spec : specs.values()) {
			if (runners.containsKey(spec.name())) continue;
			if (engine.getVenueState().users().isDeleted(spec.userDID(engine))) continue;
			try {
				AdapterState state = state(spec.userDID(engine));
				R runner = newBot(spec, Managed.CONFIG);
				state.write("config/" + spec.name() + "/user", spec.userDID(engine));
				runners.put(spec.name(), runner);
				runner.start();
			} catch (AuthException e) {
				// Deletion may commit between admission and the owner-bound write.
				if (!engine.getVenueState().users().isDeleted(spec.userDID(engine))) throw e;
			}
		}
	}

	protected final synchronized void rearmRuntimeBots() {
		if (closed) return;
		AMap<AString, ACell> users = RT.castMap(state().read("users"));
		if (users != null) for (var entry : users.entrySet()) {
			if (!(entry.getKey() instanceof AString owner) || !owner.toString().startsWith("did:")
					|| owner.toString().contains("/")) {
				log.warn("{}: skipping invalid adapter-state user key {}", getName(), entry.getKey());
				continue;
			}
			startRegistry(owner, RT.castMap(RT.getIn(entry.getValue(), K_BOTS)), false);
		}
		rearmLegacyBots();
	}

	/** Optional provider migration, performed after the canonical registry wins. */
	protected void rearmLegacyBots() { }

	protected final synchronized void startRegistry(AString owner, AMap<AString, ACell> registry, boolean migrate) {
		if (registry == null || closed || !running || engine.getVenueState().users().isDeleted(owner)) return;
		for (var entry : registry.entrySet()) {
			String name = String.valueOf(entry.getKey());
			String key = runtimeKey(owner, name);
			if (runners.containsKey(key)) continue;
			try {
				AMap<AString, ACell> stored = RT.castMap(entry.getValue());
				if (stored == null) throw new IllegalArgumentException(SETTINGS_REQUIRED);
				AString principal = RT.ensureString(stored.get(K_CALLER));
				if (principal != null && !owner.equals(Principals.userOf(principal))) {
					throw new IllegalArgumentException(FOREIGN_PRINCIPAL.formatted(principal));
				}
				AMap<AString, ACell> settings = stored.dissoc(K_CALLER);
				S spec = parseBot(name, settings.assoc(K_USER, owner), true);
				if (migrate) state(owner).write(userStatePath(owner, "bots/" + name), stored);
				if (principal != null) principals.put(key, principal);
				R runner = newBot(spec, Managed.RUNTIME);
				runners.put(key, runner);
				runner.start();
			} catch (RuntimeException e) {
				log.warn("{}: skipping bot '{}' of {}: {}", getName(), name, owner, e.getMessage());
			}
		}
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		running = false;
		try {
			stopRunners();
		} finally {
			retries.shutdownNow();
		}
	}

	public final boolean isActive() { return engine != null && engine.getAdapter(getName()) == this; }
	public final ScheduledFuture<?> scheduleRetry(Runnable task, long millis) {
		if (closed) return null;
		try { return retries.schedule(task, millis, TimeUnit.MILLISECONDS); }
		catch (java.util.concurrent.RejectedExecutionException e) {
			if (closed) return null;
			throw e;
		}
	}

	/** Provider credentials may have several names (e.g. Slack bot and app tokens). */
	public final String resolveCredential(String reference, MessagingBot<?> bot) {
		return resolveCredential(reference, bot, null);
	}

	/**
	 * As {@link #resolveCredential(String, MessagingBot)} for a credential about to be
	 * sent to {@code destination}: a {@code secret/use} grant bound to a url prefix
	 * covers another principal's secret only there. Null when the use has no destination.
	 *
	 * <p>A reference resolves under the bot owner's authority, so another principal's
	 * secret, the venue's included, needs a {@code secret/use} grant. Only an
	 * operator-declared bot also looks in the venue's own store: the operator wrote
	 * that reference, so the venue's authority stands behind it. A user-created bot's
	 * reference is its creator's, and never resolves as the venue.</p>
	 */
	public final String resolveCredential(String reference, MessagingBot<?> bot, String destination) {
		if (!isSecretReference(reference)) return reference;
		String value = engine.resolveSecret(reference, engine.context(bot.spec().userDID(engine)), destination);
		if (value != null || bot.managed() != Managed.CONFIG) return value;
		return engine.resolveSecret(reference, engine.venueContext(), destination);
	}

	protected static boolean isSecretReference(String value) {
		return Engine.isSecretRef(value);
	}

	public final AdapterState state() { return adapterState(); }
	public final AdapterState state(AString owner) { return adapterState(owner); }
	public final String userStatePath(AString owner, String relative) { return state().userPath(owner, relative); }
	public final synchronized R runner(String name) { return runners.get(name); }
	public final synchronized R runner(AString owner, String name) { return runners.get(runtimeKey(owner, name)); }
	private static String runtimeKey(AString owner, String name) { return owner + "#" + name; }
	protected final synchronized List<R> runnerList() { return new ArrayList<>(runners.values()); }

	/** Drops the live binding, preserving its durable record for re-arming. */
	protected final synchronized void forgetRuntimeBot(AString owner, String name) {
		R runner = runners.remove(runtimeKey(owner, name));
		if (runner != null) runner.stop();
	}

	protected final R selectBot(RequestContext ctx, AString name) {
		AString user = ctx.getUserDID();
		if (name != null && !name.isEmpty()) {
			R runner = user == null ? null : runner(user, name.toString());
			if (runner == null) runner = runner(name.toString());
			if (runner == null) throw new NotFoundException(UNKNOWN_BOT.formatted(providerName(), name), name.toString());
			return runner;
		}
		List<R> mine = new ArrayList<>();
		for (R runner : runnerList()) if (user != null && user.equals(runner.spec().userDID(engine))) mine.add(runner);
		if (mine.size() == 1) return mine.getFirst();
		if (mine.isEmpty()) throw new NotFoundException(NO_BOT.formatted(providerName(), user), null);
		throw new InvalidInputException(AMBIGUOUS_BOT.formatted(providerName()));
	}

	protected final void requireBotAccess(RequestContext ctx, R runner, AString ability) {
		engine.requireLocalAccess(ctx, resource(runner.spec().userDID(engine), runner.spec().name()), ability);
	}

	private AString resource(AString owner, String name) { return Strings.create(owner + "/" + getName() + "/" + name); }

	protected final ACell handleBots(RequestContext ctx) {
		// The namespace's own bots, to a request acting with that namespace's
		// authority; every bot, to the venue.
		AString user = ctx.actsWithNamespaceAuthority() ? ctx.getUserDID() : null;
		boolean venue = engine.getDIDString().equals(ctx.authorityContext().getCallerDID());
		AVector<ACell> result = Vectors.empty();
		for (R runner : runnerList()) if (venue || (user != null && user.equals(runner.spec().userDID(engine)))) result = result.conj(runner.status());
		return Maps.of(K_BOTS, result);
	}

	/** Override to require every provider-specific credential to be a secret reference. */
	protected void validateRuntimeCredentials(AMap<AString, ACell> settings) {
		AString token = RT.ensureString(settings.get(K_TOKEN));
		if (!isSecretReference(token == null ? null : token.toString())) throw new InvalidInputException(SECRET_REFERENCE_REQUIRED);
	}

	protected final synchronized ACell handleCreate(RequestContext ctx, ACell input) {
		if (closed) throw new IllegalStateException(CLOSED);
		if (!running) throw new UnavailableException(NOT_RUNNING.formatted(providerName()));
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException(getName() + ":create" + AUTH_REQUIRED);
		AMap<AString, ACell> in = RT.castMap(input);
		if (in == null) throw new InvalidInputException(SETTINGS_REQUIRED);
		AString nameCell = RT.ensureString(in.get(Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new InvalidInputException(NAME_REQUIRED);
		String name = nameCell.toString();
		if (in.containsKey(K_USER)) throw new InvalidInputException(IMPLICIT_USER);
		validateRuntimeCredentials(in);
		AMap<AString, ACell> settings = in.dissoc(Fields.NAME);
		S spec;
		try {
			spec = parseBot(name, settings.assoc(K_USER, owner), true);
		} catch (IllegalArgumentException e) {
			throw new InvalidInputException(e.getMessage(), e);
		}
		engine.requireAuthority(ctx, resource(owner, name), Strings.intern(getName() + "/manage"));
		String key = runtimeKey(owner, name);
		if (runners.containsKey(key)) throw new ConflictException(DUPLICATE_BOT.formatted(providerName(), name));
		// The bot identifies its senders as exactly its creator: an agent as itself, never its owner.
		// It then acts as that creator, so it cannot record one whose authority was another's.
		if (ctx.hasSeparateAuthority()) throw new AuthException(SEPARATE_AUTHORITY_CREATOR);
		AString principal = ctx.getCallerDID();
		if (!owner.equals(Principals.userOf(principal))) throw new AuthException(FOREIGN_PRINCIPAL.formatted(principal));
		boolean own = owner.equals(principal);
		if (own) principals.remove(key); else principals.put(key, principal);
		R runner = newBot(spec, Managed.RUNTIME);
		state(owner).write(userStatePath(owner, "bots/" + name), own ? settings : settings.assoc(K_CALLER, principal));
		runners.put(key, runner);
		runner.start();
		return runner.status();
	}

	/**
	 * Who a bot's identified senders come from: the principal that created it,
	 * or null for its user (every operator-declared bot, and a bot its user
	 * created directly).
	 */
	public final AString principalOf(S spec, Managed managed) {
		if (managed != Managed.RUNTIME) return null;
		return principals.get(runtimeKey(spec.userDID(engine), spec.name()));
	}

	protected final synchronized ACell handleDelete(RequestContext ctx, ACell input) {
		// A stopped bot has no runner to stop, and its records go only with it.
		if (!running) throw new UnavailableException(NOT_RUNNING.formatted(providerName()));
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException(getName() + ":delete" + AUTH_REQUIRED);
		AString nameCell = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new InvalidInputException(NAME_REQUIRED);
		String name = nameCell.toString();
		engine.requireAuthority(ctx, resource(owner, name), Strings.intern(getName() + "/manage"));
		R runner = runners.remove(runtimeKey(owner, name));
		if (runner == null) {
			R config = runners.get(name);
			if (config != null && owner.equals(config.spec().userDID(engine))) throw new NotSupportedException(CONFIG_BOT.formatted(name));
			throw new NotFoundException(MISSING_BOT.formatted(providerName(), name), name);
		}
		runner.stop();
		principals.remove(runtimeKey(owner, name));
		// The bot's records in the adapter's state go together, or none of them do
		state(owner).execute(draft -> {
			draft.delete(userStatePath(owner, "bots/" + name));
			draft.delete(userStatePath(owner, "sessions/" + name));
			deleteRuntimeState(draft, owner, name);
		});
		deleteLegacyState(ctx, name);
		return Maps.of(Fields.NAME, nameCell, K_DELETED, CVMBool.TRUE);
	}

	/**
	 * Removes a deleted bot's provider-specific records in the adapter's state,
	 * in the transaction that removes its shared ones.
	 *
	 * @param draft The adapter's state in that transaction
	 */
	protected void deleteRuntimeState(AdapterState draft, AString owner, String name) { }

	/** Removes records an earlier venue kept for a deleted bot outside the adapter's state, after its own go. */
	protected void deleteLegacyState(RequestContext ctx, String name) { }
}

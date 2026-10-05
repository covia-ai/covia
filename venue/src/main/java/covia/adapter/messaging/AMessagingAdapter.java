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
import covia.venue.AdapterWorkspace;
import covia.venue.RequestContext;

/**
 * Shared registration, ownership and lifecycle for messaging adapters.
 * Provider subclasses retain configuration parsing, credentials, admission,
 * transports and operation schemas. Installation is inert; {@link #start()}
 * activates workers only after the engine is ready.
 */
public abstract class AMessagingAdapter<S extends MessagingBotSpec, R extends MessagingBot<S>>
		extends AAdapter implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(AMessagingAdapter.class);
	protected static final AString K_BOTS = Strings.intern("bots");
	private static final AString K_USER = Strings.intern("user");
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

	private Map<String, S> specs = Map.of();
	private final Map<String, R> runners = new LinkedHashMap<>();
	private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, getName() + "-retry");
		t.setDaemon(true);
		return t;
	});
	private volatile boolean closed;
	/** Retry cadence for missing credentials or temporarily unavailable providers. */
	public volatile long retryMillis = 30_000;

	protected abstract S parseBot(String name, ACell settings, boolean strict);
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
		if (engine != null && engine.isStarted()) reconcile();
	}

	@Override
	public synchronized void start() {
		if (closed) return;
		reconcile();
		rearmRuntimeBots();
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
			R runner = newBot(spec, Managed.CONFIG);
			runners.put(spec.name(), runner);
			runner.start();
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
		if (registry == null || closed) return;
		for (var entry : registry.entrySet()) {
			String name = String.valueOf(entry.getKey());
			String key = runtimeKey(owner, name);
			if (runners.containsKey(key)) continue;
			try {
				AMap<AString, ACell> settings = RT.castMap(entry.getValue());
				if (settings == null) throw new IllegalArgumentException(SETTINGS_REQUIRED);
				S spec = parseBot(name, settings.assoc(K_USER, owner), true);
				if (migrate) state().write(userStatePath(owner, "bots/" + name), settings);
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
		try {
			for (R runner : runners.values()) {
				try { runner.stop(); }
				catch (RuntimeException e) { log.warn("{}: failed to stop bot '{}'", getName(), runner.spec().name(), e); }
			}
		} finally {
			runners.clear();
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
	public final String resolveCredential(String reference, AString owner) {
		if (!isSecretReference(reference)) return reference;
		String value = engine.resolveSecret(reference, RequestContext.of(owner));
		return value != null ? value : engine.resolveSecret(reference, engine.venueContext());
	}

	protected static boolean isSecretReference(String value) {
		return value != null && (value.startsWith("s/") || value.startsWith("/s/"));
	}

	public final AdapterWorkspace state() { return adapterWorkspace(); }
	public final String userStatePath(AString owner, String relative) { return state().userPath(owner, relative); }
	public final String runtimeBotPath(AString owner, String name) { return state().path(userStatePath(owner, "bots/" + name)); }
	public final synchronized R runner(String name) { return runners.get(name); }
	public final synchronized R runner(AString owner, String name) { return runners.get(runtimeKey(owner, name)); }
	private static String runtimeKey(AString owner, String name) { return owner + "#" + name; }
	private synchronized List<R> runnerList() { return new ArrayList<>(runners.values()); }

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
			if (runner == null) throw new IllegalArgumentException(UNKNOWN_BOT.formatted(providerName(), name));
			return runner;
		}
		List<R> mine = new ArrayList<>();
		for (R runner : runnerList()) if (user != null && user.equals(runner.spec().userDID(engine))) mine.add(runner);
		if (mine.size() == 1) return mine.getFirst();
		if (mine.isEmpty()) throw new IllegalArgumentException(NO_BOT.formatted(providerName(), user));
		throw new IllegalArgumentException(AMBIGUOUS_BOT.formatted(providerName()));
	}

	protected final void requireBotAccess(RequestContext ctx, R runner, AString ability) {
		engine.requireLocalAccess(ctx, resource(runner.spec().userDID(engine), runner.spec().name()), ability);
	}

	private AString resource(AString owner, String name) { return Strings.create(owner + "/" + getName() + "/" + name); }

	protected final ACell handleBots(RequestContext ctx) {
		AString user = ctx.getUserDID();
		boolean venue = engine.getDIDString().equals(ctx.getCallerDID());
		AVector<ACell> result = Vectors.empty();
		for (R runner : runnerList()) if (venue || (user != null && user.equals(runner.spec().userDID(engine)))) result = result.conj(runner.status());
		return Maps.of(K_BOTS, result);
	}

	/** Override to require every provider-specific credential to be a secret reference. */
	protected void validateRuntimeCredentials(AMap<AString, ACell> settings) {
		AString token = RT.ensureString(settings.get(K_TOKEN));
		if (!isSecretReference(token == null ? null : token.toString())) throw new IllegalArgumentException(SECRET_REFERENCE_REQUIRED);
	}

	protected final synchronized ACell handleCreate(RequestContext ctx, ACell input) {
		if (closed) throw new IllegalStateException(CLOSED);
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException(getName() + ":create" + AUTH_REQUIRED);
		AMap<AString, ACell> in = RT.castMap(input);
		if (in == null) throw new IllegalArgumentException(SETTINGS_REQUIRED);
		AString nameCell = RT.ensureString(in.get(Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new IllegalArgumentException(NAME_REQUIRED);
		String name = nameCell.toString();
		if (in.containsKey(K_USER)) throw new IllegalArgumentException(IMPLICIT_USER);
		validateRuntimeCredentials(in);
		AMap<AString, ACell> settings = in.dissoc(Fields.NAME);
		S spec = parseBot(name, settings.assoc(K_USER, owner), true);
		engine.requireAuthority(ctx, resource(owner, name), Strings.intern(getName() + "/manage"));
		String key = runtimeKey(owner, name);
		if (runners.containsKey(key)) throw new IllegalArgumentException(DUPLICATE_BOT.formatted(providerName(), name));
		R runner = newBot(spec, Managed.RUNTIME);
		state().write(userStatePath(owner, "bots/" + name), settings);
		runners.put(key, runner);
		runner.start();
		return runner.status();
	}

	protected final synchronized ACell handleDelete(RequestContext ctx, ACell input) {
		AString owner = ctx.getUserDID();
		if (owner == null) throw new AuthException(getName() + ":delete" + AUTH_REQUIRED);
		AString nameCell = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new IllegalArgumentException(NAME_REQUIRED);
		String name = nameCell.toString();
		engine.requireAuthority(ctx, resource(owner, name), Strings.intern(getName() + "/manage"));
		R runner = runners.remove(runtimeKey(owner, name));
		if (runner == null) {
			R config = runners.get(name);
			if (config != null && owner.equals(config.spec().userDID(engine))) throw new IllegalArgumentException(CONFIG_BOT.formatted(name));
			throw new IllegalArgumentException(MISSING_BOT.formatted(providerName(), name));
		}
		runner.stop();
		state().delete(userStatePath(owner, "bots/" + name));
		state().delete(userStatePath(owner, "sessions/" + name));
		deleteRuntimeState(ctx, name);
		return Maps.of(Fields.NAME, nameCell, K_DELETED, CVMBool.TRUE);
	}

	/** Remove provider-specific state and legacy records alongside the shared state. */
	protected void deleteRuntimeState(RequestContext ctx, String name) { }
}

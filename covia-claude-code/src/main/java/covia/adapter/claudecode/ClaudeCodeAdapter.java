package covia.adapter.claudecode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Claude Code adapter: lets agents and jobs drive the Claude Code CLI in
 * venue-authorised project directories.
 *
 * <p><b>Projects</b> are the unit of authorisation: a directory Claude Code
 * may run in, owned by a {@code user} (that user and their agents may run
 * there; anyone else needs a delegation from them — the gate is
 * {@code <owner>/claudecode/<project>} × {@code claudecode/run}). They are
 * declared by the operator ({@code adapters.claudecode.projects}) or created
 * at runtime with {@code claudecode:create} by a caller holding venue
 * authority ({@code <venue>/claudecode/projects} × {@code claudecode/manage}),
 * in which case they are recorded at {@code w/claudecode/projects/<name>} in
 * the venue's workspace and re-armed at boot. Naming a directory the venue's
 * OS user may execute code in is always an operator-level decision; a caller
 * never chooses a directory.</p>
 *
 * <p><b>Sessions</b> are Claude Code conversations. Their transcripts live
 * on disk (Claude Code's own), so a session outlives its process and is
 * continued with {@code --resume}. Live processes are a bounded pool
 * ({@code maxSessions}, ~400 MB each): a process stays warm between turns
 * while {@code keepAlive} is on, is reaped after {@code idleSeconds} of
 * inactivity or to make room for a new one (least recently used first), and
 * is respawned transparently on the session's next turn.</p>
 *
 * <p><b>Operations</b>:</p>
 * <ul>
 *   <li>{@code claudecode:run {project?, prompt, session?, …options}} — one
 *       turn as one Job: completes with the turn's result (text, structured
 *       output, session id, cost…). Pass {@code session} to continue a
 *       conversation. Progress ({@code progress}: tool calls, latest text) is
 *       published on the job while it runs.</li>
 *   <li>{@code claudecode:session {project?, prompt?, session?, …options}} —
 *       a long-lived Job holding a conversation: after each turn it waits in
 *       {@code INPUT_REQUIRED} with the reply as output; each job message
 *       ({@code {content}} / {@code {prompt}}) is the next turn; {@code {end:
 *       true}} finishes it.</li>
 *   <li>{@code claudecode:sessions {project?}} — the sessions the caller may
 *       see (live and recently stopped), {@code claudecode:stop {session}} —
 *       stop a live process (the session stays resumable),
 *       {@code claudecode:projects} — the projects the caller may run in,
 *       {@code claudecode:create} / {@code claudecode:delete} — the runtime
 *       project registry (venue authority).</li>
 * </ul>
 */
public class ClaudeCodeAdapter extends AAdapter implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(ClaudeCodeAdapter.class);

	public static final String NAME = "claudecode";

	static final AString K_COMMAND = Strings.intern("command");
	static final AString K_ENV = RunOptions.ENV;
	static final AString K_DEFAULTS = Strings.intern("defaults");
	static final AString K_PROJECTS = Strings.intern("projects");
	static final AString K_MAX_SESSIONS = Strings.intern("maxSessions");
	static final AString K_IDLE_SECONDS = Strings.intern("idleSeconds");
	private static final AString K_ENABLED = Strings.intern("enabled");
	private static final Set<AString> KNOWN_KEYS = Set.of(
		K_COMMAND, K_ENV, K_DEFAULTS, K_PROJECTS, K_MAX_SESSIONS, K_IDLE_SECONDS, K_ENABLED);

	static final AString K_PROJECT = ClaudeSession.K_PROJECT;
	static final AString K_SESSION = ClaudeSession.K_SESSION;
	static final AString K_PROMPT = Strings.intern("prompt");
	static final AString K_OPTIONS = ProjectSpec.K_OPTIONS;
	static final AString K_PROGRESS = Strings.intern("progress");
	static final AString K_END = Strings.intern("end");
	static final AString K_ENDED = Strings.intern("ended");
	static final AString K_STOPPED = Strings.intern("stopped");
	static final AString K_SESSIONS = Strings.intern("sessions");
	static final AString K_LIVE_SESSIONS = Strings.intern("liveSessions");
	static final AString K_DELETED = Strings.intern("deleted");
	static final AString K_KIND = Strings.intern("kind");
	static final AString KIND_SESSION = Strings.intern("session");
	private static final AString[] MESSAGE_TEXT_KEYS = { K_PROMPT, Fields.CONTENT, Fields.TEXT, Fields.MESSAGE };

	/** Ability to run Claude Code in a project; resource {@code <owner>/claudecode/<project>}. */
	public static final AString ABILITY_RUN = Strings.intern("claudecode/run");
	/** Ability to create/delete runtime projects; venue resource {@code claudecode/projects}. */
	public static final AString ABILITY_MANAGE = Strings.intern("claudecode/manage");
	/** The venue-relative resource guarding the runtime project registry. */
	static final String VENUE_RESOURCE = "claudecode/projects";
	/** Registry of runtime-created projects, in the venue's workspace. */
	static final String REGISTRY_PATH = "w/claudecode/projects";

	static final int DEFAULT_MAX_SESSIONS = 4;
	static final long DEFAULT_IDLE_SECONDS = 900;
	/** How many stopped sessions to remember (for {@code sessions} and project-less resume). */
	private static final int REMEMBERED_SESSIONS = 200;

	private volatile List<String> command = List.of("claude");
	private volatile Map<String, String> envRefs = Map.of();
	private volatile RunOptions defaults = RunOptions.EMPTY;
	private volatile Map<String, ProjectSpec> configProjects = Map.of();
	private volatile int maxSessions = DEFAULT_MAX_SESSIONS;
	private volatile long idleSeconds = DEFAULT_IDLE_SECONDS;

	/** Runtime-created projects. Guarded by {@code this}. */
	private final Map<String, ProjectSpec> runtimeProjects = new LinkedHashMap<>();

	/** The pool: every known session (live or stopped), oldest first. Guarded by {@code pool}. */
	private final Set<ClaudeSession> pool = new LinkedHashSet<>();
	/** Sessions by Claude Code session id, once known. Guarded by {@code pool}. */
	private final Map<String, ClaudeSession> byId = new LinkedHashMap<>();
	/** Live {@code claudecode:session} jobs. */
	private final Map<Blob, SessionJob> sessionJobs = new ConcurrentHashMap<>();

	private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "claudecode-reaper");
		t.setDaemon(true);
		return t;
	});
	/** Reaper period. Package-private so tests can shorten it. */
	volatile long reapPeriodMillis = 30_000;
	private volatile java.util.concurrent.ScheduledFuture<?> reapTask;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Drives the Claude Code CLI in venue-authorised project directories: one-shot runs and "
			+ "long-lived resumable sessions (claudecode:run / claudecode:session), a bounded pool of live "
			+ "processes, and a venue-managed project registry.";
	}

	@Override
	public AMap<AString, ACell> publicConfig() {
		return Maps.of(K_MAX_SESSIONS, CVMLong.create(maxSessions), K_IDLE_SECONDS, CVMLong.create(idleSeconds));
	}

	@Override
	protected void installAssets() {
		installAsset("claudecode/run", "/adapters/claudecode/run.json");
		installAsset("claudecode/session", "/adapters/claudecode/session.json");
		installAsset("claudecode/sessions", "/adapters/claudecode/sessions.json");
		installAsset("claudecode/stop", "/adapters/claudecode/stop.json");
		installAsset("claudecode/projects", "/adapters/claudecode/projects.json");
		installAsset("claudecode/create", "/adapters/claudecode/create.json");
		installAsset("claudecode/delete", "/adapters/claudecode/delete.json");
		installSkill("adapters/claudecode", "/skills/claudecode.json");
	}

	// ------------------------------------------------------------ configuration

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		if (config == null) config = Maps.empty();
		if (strict) {
			for (long i = 0; i < config.count(); i++) {
				ACell k = config.entryAt(i).getKey();
				if (!(k instanceof AString ks) || !KNOWN_KEYS.contains(ks)) {
					throw new IllegalArgumentException("adapters.claudecode: unknown setting " + k
						+ " (known: command, env, defaults, projects, maxSessions, idleSeconds, enabled)");
				}
			}
		}
		List<String> cmd = parseCommand(config.get(K_COMMAND));
		Map<String, String> env = RunOptions.parse(
			config.get(K_ENV) == null ? null : Maps.of(K_ENV, config.get(K_ENV)),
			Set.of(K_ENV), "adapters.claudecode", true).env();
		RunOptions dflt = RunOptions.parse(config.get(K_DEFAULTS), RunOptions.ALL_KEYS, "adapters.claudecode.defaults", strict);
		int max = positiveInt(config.get(K_MAX_SESSIONS), DEFAULT_MAX_SESSIONS, "adapters.claudecode.maxSessions", 1);
		long idle = positiveInt(config.get(K_IDLE_SECONDS), (int) DEFAULT_IDLE_SECONDS, "adapters.claudecode.idleSeconds", 0);
		Map<String, ProjectSpec> parsed = new LinkedHashMap<>();
		ACell projectsCell = config.get(K_PROJECTS);
		if (projectsCell != null) {
			AMap<AString, ACell> projects = RT.castMap(projectsCell);
			if (projects == null) throw new IllegalArgumentException("adapters.claudecode.projects must be an object of project name -> settings");
			for (long i = 0; i < projects.count(); i++) {
				var e = projects.entryAt(i);
				String name = String.valueOf(e.getKey());
				parsed.put(name, ProjectSpec.parse(name, e.getValue(), strict, ProjectSpec.Managed.CONFIG, ProjectSpec.VENUE_USER));
			}
		}
		this.command = cmd;
		this.envRefs = env;
		this.defaults = dflt;
		this.maxSessions = max;
		this.idleSeconds = idle;
		this.configProjects = Map.copyOf(parsed);
		if (engine != null) reconcile();
		return true;
	}

	private static List<String> parseCommand(ACell cell) {
		if (cell == null) return List.of("claude");
		if (cell instanceof AString s) {
			if (s.isEmpty()) throw new IllegalArgumentException("adapters.claudecode.command must not be empty");
			return List.of(s.toString());
		}
		AVector<ACell> v = RT.ensureVector(cell);
		if (v == null || v.isEmpty()) {
			throw new IllegalArgumentException("adapters.claudecode.command must be the claude executable (a string) or an argv array");
		}
		List<String> out = new ArrayList<>();
		for (long i = 0; i < v.count(); i++) {
			if (!(v.get(i) instanceof AString s)) throw new IllegalArgumentException("adapters.claudecode.command entries must be strings");
			out.add(s.toString());
		}
		return List.copyOf(out);
	}

	private static int positiveInt(ACell cell, int dflt, String where, int min) {
		if (cell == null) return dflt;
		CVMLong n = RT.ensureLong(cell);
		if (n == null || n.longValue() < min || n.longValue() > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(where + " must be an integer >= " + min);
		}
		return (int) n.longValue();
	}

	@Override
	public void install(Engine engine) {
		super.install(engine);
		rearmRuntimeProjects();
		reconcile();
		reapTask = reaper.scheduleWithFixedDelay(this::reapIdle, reapPeriodMillis, reapPeriodMillis, TimeUnit.MILLISECONDS);
	}

	/** After a (re)configuration: sessions whose project spec changed or vanished are stopped (they respawn against the current spec). */
	private void reconcile() {
		List<ClaudeSession> stale = new ArrayList<>();
		synchronized (pool) {
			for (ClaudeSession s : pool) {
				if (!s.isLive()) continue;
				ProjectSpec now = project(s.project.name());
				if (now == null || !now.equals(s.project)) stale.add(s);
			}
		}
		for (ClaudeSession s : stale) s.stop("project reconfigured");
	}

	/**
	 * Load the runtime project registry ({@code w/claudecode/projects} in the
	 * venue workspace). Read straight from the lattice: at boot a module
	 * installs before the ops catalog is materialised. A record that no longer
	 * parses (directory gone…) is logged and skipped.
	 */
	private synchronized void rearmRuntimeProjects() {
		AMap<AString, ACell> registry;
		try {
			registry = RT.castMap(engine.resolvePath(Strings.create(REGISTRY_PATH), engine.venueContext()));
		} catch (RuntimeException e) {
			log.warn("Claude Code: could not read the project registry: {}", e.getMessage());
			return;
		}
		if (registry == null || registry.isEmpty()) return;
		int count = 0;
		for (var e : registry.entrySet()) {
			String name = String.valueOf(e.getKey());
			try {
				runtimeProjects.put(name, ProjectSpec.parse(name, e.getValue(), false, ProjectSpec.Managed.RUNTIME, ProjectSpec.VENUE_USER));
				count++;
			} catch (RuntimeException ex) {
				log.warn("Claude Code: skipping project '{}': {}", name, ex.getMessage());
			}
		}
		if (count > 0) log.info("Claude Code: re-armed {} runtime project(s) from the lattice", count);
	}

	@Override
	public void close() {
		java.util.concurrent.ScheduledFuture<?> t = reapTask;
		if (t != null) t.cancel(false);
		reaper.shutdownNow();
		List<ClaudeSession> all;
		synchronized (pool) {
			all = new ArrayList<>(pool);
			pool.clear();
			byId.clear();
		}
		for (ClaudeSession s : all) s.stop("adapter closed");
		sessionJobs.clear();
	}

	// ----------------------------------------------------------- session support

	/** The argv prefix that runs the CLI. */
	List<String> command() {
		return command;
	}

	/**
	 * The extra environment for a process in a project: the adapter's
	 * {@code env} then the project's, {@code s/NAME} values resolved as
	 * secrets — the project's in its owner's store then the venue's, the
	 * adapter's in the venue's. A missing secret is an error, not an empty
	 * variable.
	 */
	Map<String, String> environment(ProjectSpec project) {
		Map<String, String> out = new LinkedHashMap<>();
		RequestContext venue = engine.venueContext();
		for (var e : envRefs.entrySet()) out.put(e.getKey(), resolveEnv(e.getKey(), e.getValue(), venue, null));
		RequestContext owner = RequestContext.of(project.userDID(engine));
		for (var e : project.options().env().entrySet()) {
			out.put(e.getKey(), resolveEnv(e.getKey(), e.getValue(), owner, venue));
		}
		return out;
	}

	private String resolveEnv(String name, String value, RequestContext first, RequestContext second) {
		if (!(value.startsWith("s/") || value.startsWith("/s/"))) return value;
		String resolved = engine.resolveSecret(value, first);
		if (resolved == null && second != null) resolved = engine.resolveSecret(value, second);
		if (resolved == null) {
			throw new IllegalStateException("Claude Code environment variable " + name + " refers to secret "
				+ value + ", which is not set (store it with v/ops/secret/set)");
		}
		return resolved;
	}

	/** Called by a session once Claude Code has announced its id. */
	void onSessionAnnounced(ClaudeSession s) {
		String id = s.sessionId();
		if (id == null) return;
		synchronized (pool) {
			ClaudeSession prev = byId.put(id, s);
			if (prev != null && prev != s) pool.remove(prev);
		}
	}

	/** Called by a session whenever it frees or might free a slot (turn done, idle, process ended). */
	void onSlotFreed(ClaudeSession s) {
		synchronized (pool) {
			pool.notifyAll();
		}
	}

	private void register(ClaudeSession s) {
		synchronized (pool) {
			pool.add(s);
			if (s.sessionId() != null) byId.put(s.sessionId(), s);
			// Forget the oldest stopped sessions beyond the memory.
			if (pool.size() > REMEMBERED_SESSIONS) {
				var it = pool.iterator();
				while (it.hasNext() && pool.size() > REMEMBERED_SESSIONS) {
					ClaudeSession old = it.next();
					if (old.isLive() || old == s) continue;
					it.remove();
					if (old.sessionId() != null) byId.remove(old.sessionId(), old);
				}
			}
		}
	}

	/** Sessions holding (or about to hold) a process — everything not STOPPED, excluding {@code self}. */
	private int slotsInUse(ClaudeSession self) {
		int n = 0;
		for (ClaudeSession s : pool) if (s != self && s.state() != ClaudeSession.State.STOPPED) n++;
		return n;
	}

	/**
	 * Wait until a live-process slot is free for {@code session}, reaping
	 * idle sessions (least recently used first) to make room. Returns when the
	 * session may spawn; throws {@link CancellationException} if the job is
	 * cancelled while waiting.
	 */
	private void acquireSlot(Job job, ClaudeSession session) throws InterruptedException {
		while (true) {
			ClaudeSession victim = null;
			synchronized (pool) {
				if (job.isFinished()) throw new CancellationException();
				if (session.isLive() || slotsInUse(session) < maxSessions) return;
				long oldest = Long.MAX_VALUE;
				for (ClaudeSession s : pool) {
					if (s == session || !s.isIdle()) continue;
					if (s.lastUsed() < oldest) { oldest = s.lastUsed(); victim = s; }
				}
				if (victim == null) {
					pool.wait(500);
					continue;
				}
			}
			victim.stop("making room (maxSessions=" + maxSessions + ")");
		}
	}

	private void reapIdle() {
		long idle = idleSeconds;
		if (idle <= 0) return;
		long cutoff = System.currentTimeMillis() - idle * 1000;
		List<ClaudeSession> victims = new ArrayList<>();
		synchronized (pool) {
			for (ClaudeSession s : pool) {
				if (s.isIdle() && s.lastUsed() < cutoff) victims.add(s);
			}
		}
		for (ClaudeSession s : victims) s.stop("idle for " + idle + "s");
	}

	/** Test hook: the idle reap pass. */
	void reapForTest() {
		reapIdle();
	}

	/** Test hook: the boot re-arm of runtime projects. */
	void rearmForTest() {
		rearmRuntimeProjects();
	}

	/** Test hook: drop a session job's live state (simulates a venue restart). */
	void forgetSessionJobForTest(Blob jobId) {
		sessionJobs.remove(jobId);
	}

	/** The known sessions (live and stopped), oldest first. */
	List<ClaudeSession> knownSessions() {
		synchronized (pool) {
			return new ArrayList<>(pool);
		}
	}

	// ------------------------------------------------------------------ projects

	/** A project by name: config-declared first, then runtime-created. */
	ProjectSpec project(String name) {
		if (name == null) return null;
		ProjectSpec p = configProjects.get(name);
		if (p != null) return p;
		synchronized (this) {
			return runtimeProjects.get(name);
		}
	}

	private List<ProjectSpec> allProjects() {
		List<ProjectSpec> out = new ArrayList<>(configProjects.values());
		synchronized (this) {
			for (ProjectSpec p : runtimeProjects.values()) {
				if (!configProjects.containsKey(p.name())) out.add(p);
			}
		}
		return out;
	}

	/** Gate: may this caller run Claude Code in the project? */
	private void requireProjectAccess(RequestContext ctx, ProjectSpec project) {
		engine.requireLocalAccess(ctx, Strings.create(project.userDID(engine) + "/claudecode/" + project.name()), ABILITY_RUN);
	}

	private boolean canAccess(RequestContext ctx, ProjectSpec project) {
		try {
			requireProjectAccess(ctx, project);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static String optString(ACell input, AString key) {
		ACell v = RT.getIn(input, key);
		if (v == null) return null;
		if (!(v instanceof AString s)) throw new IllegalArgumentException(key + " must be a string");
		return s.isEmpty() ? null : s.toString();
	}

	// ---------------------------------------------------------------- operations

	@Override
	public boolean supportsMultiTurn() {
		return true;
	}

	@Override
	public void invoke(Job job, RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		String subOp = getSubOperation(meta);
		if ("run".equals(subOp) || "session".equals(subOp)) {
			try {
				requireInvoke(ctx);
				startTurnJob(job, ctx, input, "session".equals(subOp));
			} catch (RuntimeException e) {
				job.fail(e);
				throw e;
			}
			return;
		}
		super.invoke(job, ctx, meta, input);
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (subOp == null) throw new IllegalArgumentException("Insufficient specification for claudecode operation");
		return switch (subOp) {
			case "run", "session" -> throw new UnsupportedOperationException(subOp + " runs through the job-aware invoke path");
			case "sessions" -> CompletableFuture.supplyAsync(() -> handleSessions(ctx, input), VIRTUAL_EXECUTOR);
			case "stop" -> CompletableFuture.supplyAsync(() -> handleStop(ctx, input), VIRTUAL_EXECUTOR);
			case "projects" -> CompletableFuture.supplyAsync(() -> handleProjects(ctx), VIRTUAL_EXECUTOR);
			case "create" -> CompletableFuture.supplyAsync(() -> handleCreate(ctx, input), VIRTUAL_EXECUTOR);
			case "delete" -> CompletableFuture.supplyAsync(() -> handleDelete(ctx, input), VIRTUAL_EXECUTOR);
			default -> throw new UnsupportedOperationException("Unsupported claudecode operation: " + subOp);
		};
	}

	/**
	 * For {@code session}, the operation's result is the first reply (or the
	 * open session's handle when started without a prompt) — the job itself
	 * stays alive for further turns. {@code run} exposes the job's output.
	 */
	@Override
	public CompletableFuture<ACell> resultFuture(Job job, AMap<AString, ACell> meta, ACell input) {
		if (!"session".equals(getSubOperation(meta))) return job.future();
		CompletableFuture<ACell> first = new CompletableFuture<>();
		Consumer<Job> listener = j -> {
			if (Status.INPUT_REQUIRED.equals(j.getStatus())) {
				first.complete(j.getData().get(Fields.OUTPUT));
			} else if (j.isFinished()) {
				if (Status.COMPLETE.equals(j.getStatus())) first.complete(j.getData().get(Fields.OUTPUT));
				else first.completeExceptionally(new covia.exception.JobFailedException(
					j.getErrorMessage() == null ? "Claude Code session ended" : j.getErrorMessage()));
			}
		};
		job.subscribe(listener);
		listener.accept(job);
		first.whenComplete((r, e) -> job.unsubscribe(listener));
		return first;
	}

	// ---- run / session

	/** The resolved intent of a run/session call. */
	private record Plan(ProjectSpec project, RunOptions options, String resumeId, String prompt) {}

	/** Resolve project, options and session for a call, and gate it. */
	private Plan plan(RequestContext ctx, ACell input, boolean promptRequired) {
		if (input != null && RT.castMap(input) == null) throw new IllegalArgumentException("input must be an object");
		String prompt = optString(input, K_PROMPT);
		if (promptRequired && prompt == null) throw new IllegalArgumentException("prompt is required: what Claude Code should do");
		String resumeId = optString(input, K_SESSION);
		if (resumeId != null && !resumeId.matches("[A-Za-z0-9-]{8,64}")) {
			throw new IllegalArgumentException("session must be a Claude Code session id");
		}
		String projectName = optString(input, K_PROJECT);
		ProjectSpec project = null;
		if (projectName != null) {
			project = project(projectName);
			if (project == null) throw new IllegalArgumentException("Unknown project: " + projectName
				+ " — claudecode:projects lists the projects you can use");
		} else if (resumeId != null) {
			ClaudeSession known;
			synchronized (pool) { known = byId.get(resumeId); }
			if (known != null) project = project(known.project.name());
			if (project == null) throw new IllegalArgumentException("Session " + resumeId
				+ " is not known to this venue (it may predate a restart) — name its project too");
		} else {
			List<ProjectSpec> mine = new ArrayList<>();
			for (ProjectSpec p : allProjects()) if (canAccess(ctx, p)) mine.add(p);
			if (mine.size() == 1) {
				project = mine.get(0);
			} else if (mine.isEmpty()) {
				throw new IllegalArgumentException("No Claude Code project is available to you — the venue "
					+ "operator declares projects under adapters.claudecode.projects (or claudecode:create with venue authority)");
			} else {
				List<String> names = new ArrayList<>();
				for (ProjectSpec p : mine) names.add(p.name());
				throw new IllegalArgumentException("Several projects are available; specify 'project': " + names);
			}
		}
		requireProjectAccess(ctx, project);

		// Options: adapter defaults <- project <- call (call keys only; the
		// call's own keys are read from the input top level for convenience,
		// or from an 'options' object).
		RunOptions base = defaults.overlay(project.options());
		AMap<AString, ACell> in = RT.castMap(input);
		AMap<AString, ACell> callOpts = Maps.empty();
		if (in != null) {
			for (long i = 0; i < in.count(); i++) {
				var e = in.entryAt(i);
				if (RunOptions.CALL_KEYS.contains(e.getKey())) callOpts = callOpts.assoc(e.getKey(), e.getValue());
			}
			ACell nested = in.get(K_OPTIONS);
			if (nested != null) {
				AMap<AString, ACell> nm = RT.castMap(nested);
				if (nm == null) throw new IllegalArgumentException("options must be an object");
				for (long i = 0; i < nm.count(); i++) {
					var e = nm.entryAt(i);
					callOpts = callOpts.assoc(e.getKey(), e.getValue());
				}
			}
		}
		RunOptions call = RunOptions.parse(callOpts.isEmpty() ? null : callOpts, RunOptions.CALL_KEYS, "input", true);
		String requestedMode = call.permissionMode();
		if (RunOptions.BYPASS_PERMISSIONS.equals(requestedMode)
				&& !RunOptions.BYPASS_PERMISSIONS.equals(base.permissionMode())) {
			throw new AuthException("permissionMode " + RunOptions.BYPASS_PERMISSIONS
				+ " is only available in a project configured with it (project '" + project.name() + "' is not)");
		}
		return new Plan(project, base.overlay(call), resumeId, prompt);
	}

	/**
	 * The session for a plan: the known session when resuming with the same
	 * settings; a fresh session object (resuming on disk if an id was given)
	 * otherwise. Does not wait for a pool slot — see {@link #acquireSlot}.
	 */
	private ClaudeSession resolveSession(RequestContext ctx, Plan plan) {
		ClaudeSession s = null;
		if (plan.resumeId() != null) {
			synchronized (pool) { s = byId.get(plan.resumeId()); }
			if (s != null && (!s.project.equals(plan.project()) || !s.options.equals(plan.options()))) {
				// Different settings: the process is restarted with the new flags; the conversation resumes.
				s.stop("settings changed");
				s = null;
			}
		}
		if (s == null) {
			s = new ClaudeSession(this, plan.project(), plan.options(), plan.resumeId(), ctx.getCallerDID());
			register(s);
		}
		return s;
	}

	private void startTurnJob(Job job, RequestContext ctx, ACell input, boolean multiTurn) {
		Plan plan = plan(ctx, input, !multiTurn);
		SessionJob sj = multiTurn ? new SessionJob(job, ctx, plan) : null;
		if (sj != null) sessionJobs.put(job.getID(), sj);
		AtomicReference<CompletableFuture<AMap<AString, ACell>>> turnRef = new AtomicReference<>();
		AtomicReference<ClaudeSession> sessionRef = new AtomicReference<>();
		job.setCancelHook(() -> {
			if (sj != null) sessionJobs.remove(job.getID());
			ClaudeSession s = sessionRef.get();
			CompletableFuture<AMap<AString, ACell>> t = turnRef.get();
			if (s != null && t != null) s.cancel(t);
			if (sj != null && s != null) s.stop("session job cancelled");
			synchronized (pool) { pool.notifyAll(); }
		});
		job.update(d -> {
			d = d.assoc(K_PROJECT, Strings.create(plan.project().name()));
			if (multiTurn) d = d.assoc(K_KIND, KIND_SESSION);
			if (plan.resumeId() != null) d = d.assoc(K_SESSION, Strings.create(plan.resumeId()));
			return d;
		});
		job.setStatus(Status.STARTED);
		ClaudeSession session = resolveSession(ctx, plan);
		sessionRef.set(session);
		if (sj != null) sj.session = session;
		VIRTUAL_EXECUTOR.execute(() -> {
			try {
				ClaudeSession s = session;
				acquireSlot(job, s);
				if (job.isFinished()) return;
				if (plan.prompt() == null) {
					// A session opened without a first prompt: wait for input.
					sj.awaitInput(Maps.of(K_PROJECT, Strings.create(plan.project().name())));
					return;
				}
				CompletableFuture<AMap<AString, ACell>> turn = s.submit(plan.prompt(), progress -> publishProgress(job, progress));
				turnRef.set(turn);
				if (job.isFinished()) { s.cancel(turn); return; }
				turn.whenComplete((res, err) -> {
					if (sj != null) sj.turnDone(res, err);
					else settleTurn(job, res, err);
				});
			} catch (Throwable e) {
				if (unwrap(e) instanceof CancellationException || unwrap(e) instanceof InterruptedException) {
					job.cancel();
				} else {
					job.fail(unwrap(e));
				}
			}
		});
	}

	private static void publishProgress(Job job, AMap<AString, ACell> progress) {
		if (job.isFinished()) return;
		try {
			job.update(d -> d.assoc(K_PROGRESS, progress));
		} catch (RuntimeException e) {
			// job may have just finished; progress is best-effort
		}
	}

	/** Complete or fail a {@code run} job from a turn's outcome. */
	private static void settleTurn(Job job, AMap<AString, ACell> res, Throwable err) {
		if (job.isFinished()) return;
		if (err != null) {
			settleJob(job, null, err);
			return;
		}
		String failure = failureOf(res);
		if (failure != null) {
			job.fail(failure);
			return;
		}
		job.update(d -> d.dissoc(K_PROGRESS));
		job.completeWith(res);
	}

	/**
	 * A turn Claude Code reports as an error ({@code is_error}: max turns,
	 * budget exhausted, execution error…) fails the job with the reason and
	 * the session id, so the caller can resume where it stopped.
	 */
	static String failureOf(AMap<AString, ACell> res) {
		if (res == null) return "Claude Code returned no result";
		if (!RT.bool(res.get(ClaudeSession.K_IS_ERROR))) return null;
		ACell subtype = res.get(ClaudeSession.K_SUBTYPE);
		ACell text = res.get(ClaudeSession.K_RESULT);
		ACell session = res.get(ClaudeSession.K_SESSION);
		StringBuilder sb = new StringBuilder("Claude Code stopped");
		if (subtype != null) sb.append(": ").append(subtype);
		if (text != null) sb.append(" — ").append(conciseDetail(text, 600));
		if (session != null) sb.append(" [session ").append(session).append(" — pass it as 'session' to continue]");
		return sb.toString();
	}

	// ---- long-lived session jobs

	/** The live state of one {@code claudecode:session} job. */
	private final class SessionJob {
		final Job job;
		final RequestContext ctx;
		final Plan plan;
		volatile ClaudeSession session;
		final AtomicInteger pending = new AtomicInteger();
		int turns;

		SessionJob(Job job, RequestContext ctx, Plan plan) {
			this.job = job;
			this.ctx = ctx;
			this.plan = plan;
		}

		/** Sit in INPUT_REQUIRED with {@code output} as the visible reply. */
		void awaitInput(AMap<AString, ACell> output) {
			if (job.isFinished()) return;
			job.update(d -> {
				d = d.dissoc(K_PROGRESS);
				d = d.assoc(Fields.OUTPUT, output);
				if (session != null && session.sessionId() != null) d = d.assoc(K_SESSION, Strings.create(session.sessionId()));
				return d.assoc(Fields.STATUS, Status.INPUT_REQUIRED);
			});
		}

		void turnDone(AMap<AString, ACell> res, Throwable err) {
			if (job.isFinished()) return;
			if (err != null) {
				sessionJobs.remove(job.getID());
				settleJob(job, null, err);
				return;
			}
			turns++;
			String failure = failureOf(res);
			AMap<AString, ACell> out = (failure == null) ? res : res.assoc(Fields.ERROR, Strings.create(failure));
			if (pending.get() > 0) {
				// More turns already queued: publish this reply but stay STARTED.
				job.update(d -> d.assoc(Fields.OUTPUT, out).dissoc(K_PROGRESS));
			} else {
				awaitInput(out);
			}
		}

		/** Next turn from a job message. */
		void message(String prompt) {
			pending.incrementAndGet();
			if (!Status.STARTED.equals(job.getStatus())) job.setStatus(Status.STARTED);
			VIRTUAL_EXECUTOR.execute(() -> {
				try {
					ClaudeSession s = session;
					if (s == null) {
						// Restored after a venue restart: resume the conversation on a fresh process.
						ProjectSpec project = project(plan.project().name());
						if (project == null) throw new IllegalStateException("Project '" + plan.project().name() + "' no longer exists");
						s = resolveSession(ctx, new Plan(project, plan.options(), storedSession(), prompt));
						session = s;
					}
					acquireSlot(job, s);        // immediate when the process is still warm
					CompletableFuture<AMap<AString, ACell>> turn = s.submit(prompt, progress -> publishProgress(job, progress));
					turn.whenComplete((res, err) -> { pending.decrementAndGet(); turnDone(res, err); });
				} catch (Throwable e) {
					pending.decrementAndGet();
					sessionJobs.remove(job.getID());
					if (unwrap(e) instanceof CancellationException) job.cancel();
					else job.fail(unwrap(e));
				}
			});
		}

		private String storedSession() {
			ACell v = job.getData().get(K_SESSION);
			return (v instanceof AString s) ? s.toString() : null;
		}

		void end() {
			sessionJobs.remove(job.getID());
			ClaudeSession s = session;
			AMap<AString, ACell> summary = Maps.of(
				K_PROJECT, Strings.create(plan.project().name()),
				K_ENDED, CVMBool.TRUE,
				Fields.TURNS, CVMLong.create(turns));
			if (s != null && s.sessionId() != null) summary = summary.assoc(K_SESSION, Strings.create(s.sessionId()));
			job.update(d -> d.dissoc(K_PROGRESS));
			job.completeWith(summary);
		}
	}

	@Override
	public void handleMessage(Job job, AMap<AString, ACell> messageRecord) {
		SessionJob sj = sessionJobs.get(job.getID());
		if (sj == null) {
			// Not one of ours (a run job), or a session job restored after a venue restart.
			if (!isSessionJob(job)) return;
			sj = restoreSessionJob(job);
			if (sj == null) return;
		}
		ACell message = messageRecord.get(Fields.MESSAGE);
		if (RT.bool(RT.getIn(message, K_END))) {
			sj.end();
			return;
		}
		String prompt = messageText(message);
		if (prompt == null) {
			log.debug("Claude Code session job {}: ignoring message without text", job.getID());
			return;
		}
		sj.message(prompt);
	}

	private static boolean isSessionJob(Job job) {
		return KIND_SESSION.equals(job.getData().get(K_KIND));
	}

	/** Rebuild the live state of a session job restored from the lattice (INPUT_REQUIRED at boot). */
	private SessionJob restoreSessionJob(Job job) {
		AMap<AString, ACell> data = job.getData();
		ACell projectName = data.get(K_PROJECT);
		ProjectSpec project = project(projectName == null ? null : projectName.toString());
		if (project == null) {
			job.fail("Claude Code project " + projectName + " no longer exists on this venue");
			return null;
		}
		AString caller = job.getCaller();
		if (caller == null) {
			job.fail("Claude Code session job has no recorded caller");
			return null;
		}
		RequestContext ctx = RequestContext.of(caller);
		try {
			requireProjectAccess(ctx, project);
		} catch (RuntimeException e) {
			job.fail(e.getMessage());
			return null;
		}
		ACell sid = data.get(K_SESSION);
		Plan plan = new Plan(project, defaults.overlay(project.options()), sid == null ? null : sid.toString(), null);
		SessionJob sj = new SessionJob(job, ctx, plan);
		SessionJob prev = sessionJobs.putIfAbsent(job.getID(), sj);
		return prev != null ? prev : sj;
	}

	/** The text of a job message: its {@code prompt}/{@code content}/{@code text}/{@code message} string, or a bare string. */
	static String messageText(ACell message) {
		if (message instanceof AString s) return s.isEmpty() ? null : s.toString();
		for (AString key : MESSAGE_TEXT_KEYS) {
			ACell v = RT.getIn(message, key);
			if (v instanceof AString s && !s.isEmpty()) return s.toString();
		}
		return null;
	}

	// ---- sessions / stop / projects

	ACell handleSessions(RequestContext ctx, ACell input) {
		String projectName = optString(input, K_PROJECT);
		if (projectName != null && project(projectName) == null) throw new IllegalArgumentException("Unknown project: " + projectName);
		Map<String, Boolean> access = new LinkedHashMap<>();
		AVector<ACell> out = Vectors.empty();
		for (ClaudeSession s : knownSessions()) {
			if (projectName != null && !projectName.equals(s.project.name())) continue;
			if (s.sessionId() == null && !s.isLive()) continue;
			ProjectSpec p = project(s.project.name());
			if (p == null) continue;
			Boolean ok = access.computeIfAbsent(p.name(), n -> canAccess(ctx, p));
			if (ok) out = out.conj(s.status());
		}
		return Maps.of(K_SESSIONS, out);
	}

	ACell handleStop(RequestContext ctx, ACell input) {
		String id = optString(input, K_SESSION);
		if (id == null) throw new IllegalArgumentException("session is required");
		ClaudeSession s;
		synchronized (pool) { s = byId.get(id); }
		if (s == null) throw new IllegalArgumentException("Unknown session: " + id);
		ProjectSpec p = project(s.project.name());
		if (p == null) throw new IllegalArgumentException("Unknown session: " + id);
		requireProjectAccess(ctx, p);
		boolean wasLive = s.isLive();
		s.stop("requested by " + ctx.getCallerDID());
		return s.status().assoc(K_STOPPED, CVMBool.create(wasLive));
	}

	ACell handleProjects(RequestContext ctx) {
		AVector<ACell> out = Vectors.empty();
		for (ProjectSpec p : allProjects()) {
			if (!canAccess(ctx, p)) continue;
			int live = 0;
			synchronized (pool) {
				for (ClaudeSession s : pool) if (s.isLive() && s.project.name().equals(p.name())) live++;
			}
			out = out.conj(p.describe(engine).assoc(K_LIVE_SESSIONS, CVMLong.create(live)));
		}
		return Maps.of(K_PROJECTS, out);
	}

	/**
	 * {@code claudecode:create}: a runtime project. Venue authority
	 * ({@code <venue>/claudecode/projects} × {@code claudecode/manage}) — a
	 * directory the venue's OS user may execute code in is an operator
	 * decision. {@code user} defaults to the caller's identity (the venue's
	 * when called as the venue). Persisted at {@code w/claudecode/projects/<name>}
	 * in the venue workspace and re-armed at boot.
	 */
	ACell handleCreate(RequestContext ctx, ACell input) {
		engine.requireVenueAuthority(ctx, VENUE_RESOURCE, ABILITY_MANAGE);
		AMap<AString, ACell> in = RT.castMap(input);
		if (in == null) throw new IllegalArgumentException("create expects an object: {name, path, user?, description?, options?}");
		AString nameCell = RT.ensureString(in.get(Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new IllegalArgumentException("name is required");
		String name = nameCell.toString();
		AString callerUser = ctx.getUserDID();
		String defaultUser = (callerUser == null || engine.getDIDString().equals(callerUser))
			? ProjectSpec.VENUE_USER : callerUser.toString();
		ProjectSpec spec = ProjectSpec.parse(name, in.dissoc(Fields.NAME), true, ProjectSpec.Managed.RUNTIME, defaultUser);
		synchronized (this) {
			if (configProjects.containsKey(name)) {
				throw new IllegalArgumentException("Project '" + name + "' is declared in the venue config; "
					+ "change it there (or with v/ops/venue/adapter/configure), not with create");
			}
			if (runtimeProjects.containsKey(name)) {
				throw new IllegalArgumentException("Project '" + name + "' already exists — delete it first to replace it");
			}
			writePath(REGISTRY_PATH + "/" + name, spec.record());
			runtimeProjects.put(name, spec);
		}
		log.info("Claude Code project '{}' created by {} at {}", name, ctx.getCallerDID(), spec.path());
		return spec.describe(engine);
	}

	/** {@code claudecode:delete}: remove a runtime project (its live sessions are stopped). Config projects are the operator's. */
	ACell handleDelete(RequestContext ctx, ACell input) {
		engine.requireVenueAuthority(ctx, VENUE_RESOURCE, ABILITY_MANAGE);
		AString nameCell = RT.ensureString(RT.getIn(input, Fields.NAME));
		if (nameCell == null || nameCell.isEmpty()) throw new IllegalArgumentException("name is required");
		String name = nameCell.toString();
		synchronized (this) {
			if (configProjects.containsKey(name)) {
				throw new IllegalArgumentException("Project '" + name + "' is declared in the venue config; "
					+ "remove it there (or with v/ops/venue/adapter/configure), not with delete");
			}
			if (runtimeProjects.remove(name) == null) throw new IllegalArgumentException("Unknown project: " + name);
			deletePath(REGISTRY_PATH + "/" + name);
		}
		List<ClaudeSession> victims = new ArrayList<>();
		synchronized (pool) {
			for (ClaudeSession s : pool) if (s.project.name().equals(name)) victims.add(s);
			pool.removeAll(victims);
			byId.values().removeAll(victims);
		}
		for (ClaudeSession s : victims) s.stop("project deleted");
		log.info("Claude Code project '{}' deleted by {}", name, ctx.getCallerDID());
		return Maps.of(Fields.NAME, Strings.create(name), K_DELETED, CVMBool.TRUE);
	}

	private void writePath(String path, ACell value) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), engine.venueContext()).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new covia.exception.JobFailedException("Could not persist " + path + ": " + describeFailure(e));
		}
	}

	private void deletePath(String path) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/delete",
				Maps.of(Fields.PATH, Strings.create(path)), engine.venueContext()).get(10, TimeUnit.SECONDS);
		} catch (Exception e) {
			log.warn("Claude Code: could not delete {}: {}", path, describeFailure(e));
		}
	}
}

package covia.adapter;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Cells;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.util.CellExplorer;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.grid.Asset;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Assembles the LLM context for an agent turn.
 *
 * <p>Extracts context assembly from {@link LLMAgentAdapter#processChat} into a
 * testable, budget-aware builder. Each {@code with*} method adds a section to
 * the prompt and tracks bytes consumed via {@link Cells#storageSize}.</p>
 *
 * <p>Uses {@link CellExplorer} for budget-controlled JSON5 rendering of lattice
 * values when resolving context entries.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * ContextBuilder builder = new ContextBuilder(engine, ctx);
 * ContextResult result = builder
 *     .withConfig(recordConfig, state)
 *     .withSystemPrompt(existingHistory)
 *     .withContextEntries(state)
 *     .withPendingResults(pending)
 *     .withInboxMessages(messages)
 *     .withEmptyStateSignal(hasInput)
 *     .withTools()
 *     .build();
 * }</pre>
 */
public class ContextBuilder {

	private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

	/** Default context budget in bytes (storage-size units). ~45k tokens. */
	public static final long DEFAULT_BUDGET = 180_000;

	/** Minimum per-entry budget for CellExplorer rendering */
	private static final int MIN_ENTRY_BUDGET = 256;

	// Interned keys (matching LLMAgentAdapter constants)
	private static final AString K_CONFIG        = Strings.intern("config");
	private static final AString K_SYSTEM_PROMPT = Strings.intern("systemPrompt");
	private static final AString K_TOOLS         = Strings.intern("tools");
	private static final AString K_DEFAULT_TOOLS = Strings.intern("defaultTools");
	private static final AString K_CONTEXT       = Strings.intern("context");
	private static final AString K_CAPS          = Strings.intern("caps");
	private static final AString K_ROLE          = Strings.intern("role");
	private static final AString K_CONTENT       = Strings.intern("content");
	private static final AString K_NAME          = Strings.intern("name");
	private static final AString K_DESCRIPTION   = Strings.intern("description");
	private static final AString K_PARAMETERS    = Strings.intern("parameters");
	private static final AString K_TYPE          = Strings.intern("type");
	private static final AString K_PROPERTIES    = Strings.intern("properties");

	private static final AString ROLE_SYSTEM = Strings.intern("system");
	private static final AString ROLE_USER   = Strings.intern("user");

	/**
	 * Fallback identity prompt used when an agent has no {@code systemPrompt}
	 * in its config. Always followed by {@link #LATTICE_REFERENCE}.
	 */
	private static final AString DEFAULT_IDENTITY_PROMPT = Strings.create(
		"You are a helpful AI agent on the Covia platform. Use tools and grid "
		+ "operations to complete tasks efficiently. Give concise, clear, "
		+ "accurate responses.");

	/**
	 * Lattice reference appended to every agent's system prompt. Teaches any
	 * agent how to work on the Covia grid: what the lattice is, what each
	 * namespace is for, how addressing works across users and venues.
	 */
	private static final AString LATTICE_REFERENCE = Strings.create(
		"## Covia Lattice\n"
		+ "You operate on the Covia grid — a federated network of venues hosting "
		+ "operations, agents, and persistent data.\n"
		+ "\n"
		+ "User namespaces (scoped to current user):\n"
		+ "  w/  Workspace — persistent data you manage on the user's behalf\n"
		+ "  o/  Operation pins — named operations saved for reuse\n"
		+ "  n/  Agent-private — your notes, plans, state (persists across transitions)\n"
		+ "  t/  Temporary — job-scoped scratch space (cleaned up when job ends)\n"
		+ "  g/  Agent records — state, timelines, config for all agents\n"
		+ "  s/  Secrets — API keys and credentials (secret tools only)\n"
		+ "  j/  Job records — status and results of past work\n"
		+ "  a/  Assets — immutable content-addressed artifacts, referenced by hash\n"
		+ "\n"
		+ "Venue-level (shared, read-only):\n"
		+ "  v/ops/  Operations catalog\n"
		+ "  v/info/  Venue metadata\n"
		+ "\n"
		+ "Addressing — any path argument accepts:\n"
		+ "  w/docs/rules                  Local user data\n"
		+ "  o/my-pipeline                 Pinned operation\n"
		+ "  v/ops/covia/read              Venue operation\n"
		+ "  a/<hash>                      Asset by content hash\n"
		+ "  did:key:<id>/w/...            Cross-user (requires capability)\n"
		+ "  did:web:<venue>/v/ops/...     Cross-venue (federated)");

	/**
	 * Per-engine cache of resolved default tool definitions.
	 *
	 * <p>The default tool list ({@link #DEFAULT_TOOL_OPS}) resolves to the
	 * same {@code (tools, toolMap)} pair on every call, since all default
	 * ops live under {@code v/ops/} which is venue-scoped and independent
	 * of the calling user. Building it costs ~18 lattice lookups + JSON
	 * parses per turn. This static cache eliminates that work after the
	 * first call per Engine instance.</p>
	 *
	 * <p>Keyed on {@link Engine} identity (the default
	 * {@code ConcurrentHashMap} equality semantics, which fall through to
	 * {@code Object.equals} for {@code Engine} since it doesn't override
	 * it). Different test engines and the production engine each get
	 * their own cache entry.</p>
	 *
	 * <p>The cache is populated lazily on first {@link #withTools()} call
	 * and never invalidated — adapters install their assets at engine
	 * startup and don't change them, so cached results stay valid for
	 * the life of the JVM. Restart to refresh.</p>
	 */
	private static final java.util.concurrent.ConcurrentHashMap<Engine, DefaultToolsCacheEntry>
		DEFAULT_TOOL_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/** Cached resolved default tools for one Engine instance. */
	private static final class DefaultToolsCacheEntry {
		final AVector<ACell> tools;
		final Map<String, AString> toolMap;
		DefaultToolsCacheEntry(AVector<ACell> tools, Map<String, AString> toolMap) {
			this.tools = tools;
			// Defensive copy so callers can mutate their own configToolMap
			this.toolMap = Map.copyOf(toolMap);
		}
	}

	/** Default tool operations — resolved at runtime via engine */
	static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("v/ops/agent/create"),
		(ACell) Strings.create("v/ops/agent/message"),
		(ACell) Strings.create("v/ops/agent/request"),
		(ACell) Strings.create("v/ops/asset/store"),
		(ACell) Strings.create("v/ops/asset/get"),
		(ACell) Strings.create("v/ops/asset/list"),
		(ACell) Strings.create("v/ops/asset/content"),
		(ACell) Strings.create("v/ops/asset/pin"),
		(ACell) Strings.create("v/ops/grid/run"),
		(ACell) Strings.create("v/ops/covia/read"),
		(ACell) Strings.create("v/ops/covia/write"),
		(ACell) Strings.create("v/ops/covia/delete"),
		(ACell) Strings.create("v/ops/covia/append"),
		(ACell) Strings.create("v/ops/covia/slice"),
		(ACell) Strings.create("v/ops/covia/list"),
		(ACell) Strings.create("v/ops/covia/inspect"),
		(ACell) Strings.create("v/ops/schema/validate"),
		(ACell) Strings.create("v/ops/schema/infer")
	);

	// Instance state
	private final Engine engine;
	private final RequestContext ctx;
	private final long totalBudget;

	private long consumed = 0;
	@SuppressWarnings("unchecked")
	private AVector<ACell> messages = (AVector<ACell>) Vectors.empty();
	private AVector<ACell> tools = Vectors.empty();
	private Map<String, AString> configToolMap = new HashMap<>();
	private AVector<ACell> caps;
	private AMap<AString, ACell> config;

	/** Tool names to skip during resolution (handled externally, e.g. harness tools) */
	private java.util.Set<String> skipToolNames = java.util.Set.of();

	public ContextBuilder(Engine engine, RequestContext ctx) {
		this(engine, ctx, DEFAULT_BUDGET);
	}

	public ContextBuilder(Engine engine, RequestContext ctx, long budget) {
		this.engine = engine;
		this.ctx = ctx;
		this.totalBudget = budget;
	}

	/**
	 * Sets tool names that should be silently skipped during resolution.
	 * Used for harness tools that are resolved externally by the adapter.
	 */
	public ContextBuilder withSkipToolNames(java.util.Set<String> names) {
		this.skipToolNames = names;
		return this;
	}

	// ========== Section builders ==========

	/**
	 * Merges record-level and state-level config. Must be called first.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withConfig(AMap<AString, ACell> recordConfig, ACell state) {
		AMap<AString, ACell> stateConfig = extractConfig(state);
		if (recordConfig == null)      this.config = stateConfig;
		else if (stateConfig == null)  this.config = recordConfig;
		else                           this.config = recordConfig.merge(stateConfig);
		return this;
	}

	/**
	 * Always builds a fresh system message at the start of the LLM context.
	 *
	 * <p>The system message is composed of the agent's identity prompt
	 * (custom from config, or {@link #DEFAULT_IDENTITY_PROMPT}) followed by
	 * the {@link #LATTICE_REFERENCE} cheat sheet. This is rebuilt every
	 * turn so updates to either source apply immediately to existing
	 * agents — there is no "freeze on first turn" caching.</p>
	 *
	 * <p>The {@code starting} parameter is the message vector to start
	 * from — typically empty (callers add history via
	 * {@link #withSessionHistory(AVector)}). If a non-empty starting vector is
	 * passed and already begins with a system message, that system
	 * message is REPLACED by the freshly composed one — the rest of the
	 * starting vector is preserved.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withSystemPrompt(AVector<ACell> starting) {
		messages = (starting != null) ? starting : (AVector<ACell>) Vectors.empty();

		// If starting already has a system message at index 0, drop it —
		// we always rebuild fresh.
		if (messages.count() > 0 && ROLE_SYSTEM.equals(RT.getIn(messages.get(0), K_ROLE))) {
			messages = messages.slice(1, messages.count());
		}

		// Compose identity + lattice reference + caps section. The lattice
		// reference is always appended so every agent has namespace prefixes,
		// discovery hints, and resolution rules. The caps section is only
		// added if the agent has declared caps — without it the LLM has to
		// discover its capability boundaries by hitting them, which wastes
		// iterations and produces failure loops.
		AString identity = getConfigValue(config, K_SYSTEM_PROMPT, null);
		if (identity == null) identity = DEFAULT_IDENTITY_PROMPT;
		StringBuilder sb = new StringBuilder(identity.toString());

		// Session context: date/time and venue identity. Always included —
		// agents that write timestamps, evaluate dates, or need to refer
		// to their venue have no other way to get this information.
		sb.append("\n\nCurrent date: ")
		  .append(java.time.LocalDate.now().toString())
		  .append(". Venue: ").append(engine.getName());
		// Model name, if configured — helps the LLM self-calibrate
		AString model = getConfigValue(config, Strings.intern("model"), null);
		if (model != null) sb.append(". Model: ").append(model);
		sb.append('.');

		// Include lattice reference when the agent has tools (default or explicit).
		// Agents with no tools (e.g. pure extraction) don't need namespace docs.
		boolean hasTools = config == null
			|| !CVMBool.FALSE.equals(config.get(K_DEFAULT_TOOLS))
			|| config.get(K_TOOLS) != null;
		if (hasTools) {
			sb.append("\n\n").append(LATTICE_REFERENCE.toString());
		}
		String capsSection = renderCapsForPrompt(
			RT.ensureVector(config != null ? config.get(K_CAPS) : null));
		if (capsSection != null) {
			sb.append("\n\n").append(capsSection);
		}
		AString sysContent = Strings.create(sb.toString());
		ACell sysMsg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, sysContent);
		messages = (AVector<ACell>) Vectors.of(sysMsg).concat(messages);
		trackMessage(sysMsg);

		// Track remaining starting messages
		for (long i = 1; i < messages.count(); i++) {
			trackMessage(messages.get(i));
		}
		return this;
	}

	/**
	 * Appends session.history turn envelopes as LLM messages.
	 *
	 * <p>Each entry is a turn envelope {@code {role, content, ts, source}}.
	 * Role maps directly to the LLM role. Content is stringified if not
	 * already an {@code AString}. {@code ts} and {@code source} are
	 * dropped (vendor APIs require only {@code {role, content}}).</p>
	 *
	 * <p>If {@code turns} is null or empty, returns silently.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withSessionHistory(AVector<ACell> turns) {
		if (turns == null || turns.count() == 0) return this;
		for (long i = 0; i < turns.count(); i++) {
			ACell turn = turns.get(i);
			if (!(turn instanceof AMap)) continue;
			AMap<AString, ACell> envelope = (AMap<AString, ACell>) turn;
			ACell role = envelope.get(K_ROLE);
			if (!(role instanceof AString)) continue;
			ACell content = envelope.get(K_CONTENT);
			AString contentStr;
			if (content instanceof AString s) {
				contentStr = s;
			} else if (content == null) {
				contentStr = Strings.EMPTY;
			} else {
				contentStr = Strings.create(content.toString());
			}
			ACell msg = Maps.of(K_ROLE, role, K_CONTENT, contentStr);
			messages = messages.conj(msg);
			trackMessage(msg);
		}
		return this;
	}

	/**
	 * Resolves context entries from config and state using ContextLoader with CellExplorer.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withContextEntries(ACell state) {
		ContextLoader loader = new ContextLoader(engine);

		// Set up CellExplorer with a reasonable per-entry budget
		int entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, getRemaining() / 20);
		loader.setCellExplorer(new CellExplorer(entryBudget));

		if (config != null) {
			AVector<ACell> configContext = RT.ensureVector(config.get(K_CONTEXT));
			AVector<ACell> contextMsgs = loader.resolve(configContext, ctx);
			for (long i = 0; i < contextMsgs.count(); i++) {
				ACell msg = contextMsgs.get(i);
				messages = messages.conj(msg);
				trackMessage(msg);
			}
		}
		AVector<ACell> stateContext = RT.ensureVector(RT.getIn(state, K_CONTEXT));
		if (stateContext != null) {
			AVector<ACell> contextMsgs = loader.resolve(stateContext, ctx);
			for (long i = 0; i < contextMsgs.count(); i++) {
				ACell msg = contextMsgs.get(i);
				messages = messages.conj(msg);
				trackMessage(msg);
			}
		}
		return this;
	}

	/**
	 * Resolves loaded paths and returns them as a vector of messages.
	 * Does not modify builder state — suitable for GoalTreeAdapter which
	 * assembles its own message vector.
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> resolveLoads(AMap<AString, ACell> loads) {
		if (loads == null || loads.count() == 0) return Vectors.empty();

		ContextLoader loader = new ContextLoader(engine);
		AVector<ACell> result = Vectors.empty();

		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();

			int entryBudget = 500;
			ACell budgetCell = meta.get(Strings.intern("budget"));
			if (budgetCell instanceof convex.core.data.prim.CVMLong l) {
				entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, Math.min(l.longValue(), 10_000));
			}

			loader.setCellExplorer(new CellExplorer(entryBudget));
			ACell msg = loader.resolveEntry(path, ctx);
			if (msg != null) {
				result = result.conj(msg);
			}
		}
		return result;
	}

	/**
	 * Resolves dynamically loaded paths from the agent's state.loads map.
	 * Each entry is resolved fresh using ContextLoader with per-entry CellExplorer budget.
	 * Entries that exceed remaining budget or fail to resolve are silently skipped.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withLoadedPaths(AMap<AString, ACell> loads) {
		if (loads == null || loads.count() == 0) return this;

		ContextLoader loader = new ContextLoader(engine);

		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();

			int entryBudget = 500;
			ACell budgetCell = meta.get(Strings.intern("budget"));
			if (budgetCell instanceof convex.core.data.prim.CVMLong l) {
				entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, Math.min(l.longValue(), 10_000));
			}

			if (entryBudget > getRemaining()) continue;

			loader.setCellExplorer(new CellExplorer(entryBudget));
			ACell msg = loader.resolveEntry(path, ctx);
			if (msg != null) {
				messages = messages.conj(msg);
				trackMessage(msg);
			}
		}
		return this;
	}

	/**
	 * Appends a compact context map showing budget status and loaded paths.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withContextMap(AMap<AString, ACell> loads) {
		StringBuilder sb = new StringBuilder("[Context Map]\n");
		sb.append("budget: ").append(consumed).append("/").append(totalBudget)
		  .append(" bytes (").append(getRemaining()).append(" remaining)\n");

		int pct = (int) (100 * consumed / totalBudget);
		if (pct >= 70) {
			sb.append("WARNING: ").append(pct).append("% budget used. Consider unloading unused paths.\n");
		}

		if (loads != null && loads.count() > 0) {
			sb.append("loaded:\n");
			for (var entry : loads.entrySet()) {
				AString path = entry.getKey();
				AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();
				ACell budgetCell = meta.get(Strings.intern("budget"));
				int budget = (budgetCell instanceof convex.core.data.prim.CVMLong l) ? (int) l.longValue() : 500;
				AString label = RT.ensureString(meta.get(Strings.intern("label")));
				sb.append("  ").append(path);
				if (label != null) sb.append(" — ").append(label);
				sb.append(" [").append(budget).append("B]\n");
			}
		}

		ACell msg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, Strings.create(sb.toString()));
		messages = messages.conj(msg);
		trackMessage(msg);
		return this;
	}

	/**
	 * Safety valve: if budget usage exceeds 90%, auto-prune loaded paths (LIFO — newest first)
	 * until usage drops below 70%. Returns the pruned loads map for persistence.
	 */
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> applySafetyValve(AMap<AString, ACell> loads) {
		if (loads == null || loads.count() == 0) return loads;
		double usageRatio = (double) consumed / totalBudget;
		if (usageRatio < PRUNE_THRESHOLD) return loads;

		// Sort by ts descending (newest first for LIFO eviction)
		java.util.List<java.util.Map.Entry<AString, ACell>> sorted = new java.util.ArrayList<>();
		for (var entry : loads.entrySet()) sorted.add(entry);
		sorted.sort((a, b) -> {
			long tsA = extractTs((AMap<AString, ACell>) a.getValue());
			long tsB = extractTs((AMap<AString, ACell>) b.getValue());
			return Long.compare(tsB, tsA);
		});

		AMap<AString, ACell> pruned = loads;
		for (var e : sorted) {
			if ((double) consumed / totalBudget < WARN_THRESHOLD) break;
			AString path = e.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) e.getValue();
			int entryBudget = 500;
			ACell budgetCell = meta.get(Strings.intern("budget"));
			if (budgetCell instanceof convex.core.data.prim.CVMLong l) entryBudget = (int) l.longValue();
			pruned = pruned.dissoc(path);
			consumed -= entryBudget;
			log.info("Safety valve: auto-pruned loaded path {} ({} bytes)", path, entryBudget);
		}
		return pruned;
	}

	private static long extractTs(AMap<AString, ACell> meta) {
		ACell v = meta.get(Strings.intern("ts"));
		return (v instanceof convex.core.data.prim.CVMLong l) ? l.longValue() : 0;
	}

	static final double WARN_THRESHOLD = 0.70;
	static final double PRUNE_THRESHOLD = 0.90;

	/**
	 * Appends pending job results as a user message.
	 */
	public ContextBuilder withPendingResults(AVector<ACell> pending) {
		if (pending != null && pending.count() > 0) {
			StringBuilder sb = new StringBuilder("[Pending job results]\n");
			for (long i = 0; i < pending.count(); i++) {
				ACell p = pending.get(i);
				AString jobId = RT.ensureString(RT.getIn(p, Fields.JOB_ID));
				ACell status = RT.getIn(p, Fields.STATUS);
				ACell output = RT.getIn(p, Fields.OUTPUT);
				sb.append("- Job ").append(jobId).append(" status=").append(status)
				  .append(" output=").append(output).append("\n");
			}
			ACell msg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(sb.toString()));
			messages = messages.conj(msg);
			trackMessage(msg);
		}
		return this;
	}

	/**
	 * Appends each inbox message as a user turn with provenance.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withInboxMessages(AVector<ACell> inboxMessages) {
		if (inboxMessages == null) return this;
		for (long i = 0; i < inboxMessages.count(); i++) {
			ACell msg = inboxMessages.get(i);
			AString content = null;
			if (msg instanceof AString s) {
				content = s;
			} else if (msg instanceof AMap) {
				ACell caller = RT.getIn(msg, Fields.CALLER);
				ACell msgBody = RT.getIn(msg, Fields.MESSAGE);
				if (msgBody != null) {
					String prefix = (caller != null) ? "[Message from: " + caller + "]\n" : "[Message]\n";
					content = Strings.create(prefix + msgBody);
				} else {
					AString c = RT.ensureString(RT.getIn(msg, K_CONTENT));
					if (c != null) content = c;
					else content = Strings.create(msg.toString());
				}
			}
			if (content != null) {
				ACell userMsg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT, content);
				messages = messages.conj(userMsg);
				trackMessage(userMsg);
			}
		}
		return this;
	}

	/**
	 * Signals empty state when no input was provided.
	 */
	public ContextBuilder withEmptyStateSignal(boolean hasInput) {
		if (!hasInput) {
			ACell msg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT,
				Strings.create("[No pending tasks, messages, or job results. "
					+ "You may act proactively based on your role, or report idle.]"));
			messages = messages.conj(msg);
			trackMessage(msg);
		}
		return this;
	}

	/**
	 * Builds tool list from config (defaults + config tools) and extracts caps.
	 *
	 * <p>The default-tool resolution is cached per Engine via
	 * {@link #DEFAULT_TOOL_CACHE}: the first call per engine builds the 18
	 * default tool definitions; subsequent calls reuse them. Per-config
	 * tools are still rebuilt fresh each call since they may include
	 * user-scoped {@code /o/<name>} references.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withTools() {
		boolean useDefaults = config == null || !CVMBool.FALSE.equals(config.get(K_DEFAULT_TOOLS));
		configToolMap = new HashMap<>();

		AVector<ACell> baseTools = Vectors.empty();
		if (useDefaults) {
			DefaultToolsCacheEntry cached = DEFAULT_TOOL_CACHE.get(engine);
			if (cached == null) {
				// Cache miss: build the default tools and store the result
				Map<String, AString> freshMap = new HashMap<>();
				AVector<ACell> freshTools = buildConfigTools(DEFAULT_TOOL_OPS, freshMap);
				cached = new DefaultToolsCacheEntry(freshTools, freshMap);
				DEFAULT_TOOL_CACHE.put(engine, cached);
			}
			baseTools = cached.tools;
			// Copy the cached tool map into our per-build map so callers
			// can mutate it (e.g. add config tools below) without poisoning
			// the cache. The cache's own map is immutable (Map.copyOf).
			configToolMap.putAll(cached.toolMap);
		}

		if (config != null) {
			ACell toolsCell = config.get(K_TOOLS);
			if (toolsCell instanceof AVector<?> toolsVec) {
				// Build config tools, skipping any already provided by defaults
				AVector<ACell> configTools = buildConfigTools((AVector<ACell>) toolsVec, configToolMap);
				// Deduplicate: configToolMap was populated by defaults first, so
				// buildConfigTools only adds genuinely new entries. But the vector
				// may still contain dups if the same operation resolved to an
				// already-present tool name. Filter by checking baseTools count.
				if (baseTools.count() == 0) {
					baseTools = configTools;
				} else {
					// Only append tools whose name isn't already in baseTools
					java.util.Set<String> existing = new java.util.HashSet<>();
					for (long j = 0; j < baseTools.count(); j++) {
						ACell t = baseTools.get(j);
						if (t instanceof AMap) {
							ACell n = ((AMap<?,?>) t).get(Fields.NAME);
							if (n != null) existing.add(n.toString());
						}
					}
					for (long j = 0; j < configTools.count(); j++) {
						ACell t = configTools.get(j);
						String n = null;
						if (t instanceof AMap) {
							ACell nc = ((AMap<?,?>) t).get(Fields.NAME);
							if (nc != null) n = nc.toString();
						}
						if (n == null || !existing.contains(n)) {
							baseTools = (AVector<ACell>) baseTools.conj(t);
						}
					}
				}
			}
		}

		this.tools = baseTools;
		this.caps = RT.ensureVector(config != null ? config.get(K_CAPS) : null);
		return this;
	}

	// ========== Output ==========

	/**
	 * Returns the assembled context result.
	 */
	public ContextResult build() {
		RequestContext capsCtx = (caps != null) ? ctx.withCaps(caps) : ctx;
		return new ContextResult(
			messages, tools, configToolMap, caps, capsCtx, config,
			consumed, totalBudget - consumed);
	}

	// ========== Budget tracking ==========

	public long getConsumed() { return consumed; }
	public long getRemaining() { return totalBudget - consumed; }

	private void trackMessage(ACell msg) {
		consumed += Cells.storageSize(msg);
	}

	// ========== Caps rendering ==========

	/**
	 * Renders an agent's capability set as a system prompt section.
	 *
	 * <p>Returns {@code null} when caps are absent (full access — no
	 * restriction to communicate). For an empty array (deny-all) and any
	 * non-empty caps array, returns a labelled section listing each
	 * {@code (ability, resource)} pair plus a directive instructing the
	 * LLM not to retry calls outside its bounds.</p>
	 *
	 * <p>The format is intentionally compact and terminal: "denied" plus
	 * "retrying does not help" plus "complete with explanation if the goal
	 * cannot be met". Models that wasted iterations on cap-denied loops
	 * before this section was introduced typically did so because they had
	 * no upfront knowledge of where the boundaries actually were.</p>
	 */
	static String renderCapsForPrompt(AVector<ACell> caps) {
		if (caps == null) return null; // unrestricted — no section
		StringBuilder sb = new StringBuilder("## Your capabilities (caps)\n");
		if (caps.count() == 0) {
			sb.append("- (none) — you have no tool capabilities. Any tool call will be denied.\n");
		} else {
			for (long i = 0; i < caps.count(); i++) {
				if (!(caps.get(i) instanceof AMap<?,?> capMap)) continue;
				@SuppressWarnings("unchecked")
				AMap<AString, ACell> cap = (AMap<AString, ACell>) capMap;
				AString with = RT.ensureString(cap.get(Strings.intern("with")));
				AString can  = RT.ensureString(cap.get(Strings.intern("can")));
				if (can == null && with == null) continue;
				sb.append("- ").append(can != null ? can.toString() : "(any)")
				  .append(" on ").append(with != null ? with.toString() : "(any)")
				  .append('\n');
			}
		}
		sb.append('\n')
		  .append("Tool calls outside these capabilities will fail with a "
		  		+ "\"Capability denied\" error. Retrying the same call does not help "
		  		+ "— the denial is structural. Plan your tool calls within these "
		  		+ "bounds. If your goal cannot be achieved within your capabilities, "
		  		+ "complete the goal with a clear explanation rather than looping "
		  		+ "on impossible operations.");
		return sb.toString();
	}

	// ========== Tool building (moved from LLMAgentAdapter) ==========

	/**
	 * Resolves config tools to LLM tool definitions.
	 *
	 * <p>Each entry in {@code toolsVec} is either a string (operation path) or
	 * a map with {@code operation}, optional {@code name}, optional {@code description}.
	 * The resolved tool definitions are returned; the {@code toolMap} is populated
	 * as a side-effect (tool name → operation path) for dispatch routing.</p>
	 */
	@SuppressWarnings("unchecked")
	public AVector<ACell> buildConfigTools(AVector<ACell> toolsVec, Map<String, AString> toolMap) {
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < toolsVec.count(); i++) {
			ACell entry = toolsVec.get(i);

			AString[] parsed = parseConfigToolEntry(entry);
			if (parsed == null) continue;

			AString operation = parsed[0];
			AString nameOverride = parsed[1];
			AString descOverride = parsed[2];

			// Skip harness tools — they're resolved by the adapter, not here
			if (skipToolNames.contains(operation.toString())) continue;

			Asset asset = engine.resolveAsset(operation, ctx);
			if (asset == null) {
				log.warn("Config tool: cannot resolve operation '{}'", operation);
				continue;
			}

			// toolName lives inside the operation block, not at the top level.
			// If absent, sanitise operation.adapter (dispatch string like
			// "agent:create") rather than the catalog path used for lookup —
			// the dispatch string sanitises cleanly via colon→underscore.
			AString assetToolName = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.TOOL_NAME));
			AString dispatchAdapter = RT.ensureString(RT.getIn(asset.meta(), Fields.OPERATION, Fields.ADAPTER));
			AString fallbackSource = (dispatchAdapter != null) ? dispatchAdapter : operation;
			String toolName = deriveToolName(nameOverride, assetToolName, fallbackSource);
			toolMap.put(toolName, operation);

			// Prepend the catalog path to the description so the LLM sees the
			// lattice address co-located with the tool name. This lets the
			// model reason about provenance, discover sibling ops, and pin or
			// copy tools to its own /o/ namespace using the path it can see.
			AString rawDescription = (descOverride != null)
				? descOverride
				: RT.ensureString(asset.meta().get(Fields.DESCRIPTION));
			String descBody = (rawDescription != null) ? rawDescription.toString() : "";
			AString description = Strings.create("Operation: " + operation + "\n\n" + descBody);

			ACell inputSchema = RT.getIn(asset.meta(), Fields.OPERATION, Fields.INPUT);

			AMap<AString, ACell> toolDef = buildToolDefinition(toolName, description, inputSchema);
			result = result.conj(toolDef);
			toolMap.put(toolName, operation);
		}
		return result;
	}

	/**
	 * Parses a config tool entry (string or map) into its components.
	 *
	 * @return Array of [operation, nameOverride, descOverride], or null if invalid
	 */
	@SuppressWarnings("unchecked")
	static AString[] parseConfigToolEntry(ACell entry) {
		AString operation;
		AString nameOverride = null;
		AString descOverride = null;

		if (entry instanceof AString s) {
			operation = s;
		} else if (entry instanceof AMap<?, ?> m) {
			AMap<AString, ACell> map = (AMap<AString, ACell>) m;
			operation = RT.ensureString(map.get(Fields.OPERATION));
			nameOverride = RT.ensureString(map.get(K_NAME));
			descOverride = RT.ensureString(map.get(K_DESCRIPTION));
		} else {
			return null;
		}

		if (operation == null) return null;
		return new AString[] { operation, nameOverride, descOverride };
	}

	/**
	 * Derives a tool name from overrides, asset metadata, or the operation name.
	 * Priority: nameOverride → asset toolName → operation with colons/slashes→underscores
	 */
	static String deriveToolName(AString nameOverride, AString assetToolName, AString operation) {
		if (nameOverride != null) return nameOverride.toString();
		if (assetToolName != null) return assetToolName.toString();
		return operation.toString().replace(':', '_').replace('/', '_');
	}

	/**
	 * Builds a tool definition map from resolved components.
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> buildToolDefinition(String toolName, AString description, ACell inputSchema) {
		AMap<AString, ACell> parameters;
		if (inputSchema instanceof AMap) {
			parameters = (AMap<AString, ACell>) inputSchema;
		} else {
			parameters = Maps.of(K_TYPE, Strings.create("object"), K_PROPERTIES, Maps.empty());
		}

		AMap<AString, ACell> toolDef = Maps.of(
			K_NAME, Strings.create(toolName),
			K_PARAMETERS, parameters);
		if (description != null) {
			toolDef = toolDef.assoc(K_DESCRIPTION, description);
		}
		return toolDef;
	}

	// ========== Config helpers ==========

	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> extractConfig(ACell state) {
		ACell c = RT.getIn(state, K_CONFIG);
		return (c instanceof AMap) ? (AMap<AString, ACell>) c : null;
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString v = RT.ensureString(config.get(key));
		return (v != null) ? v : defaultValue;
	}

	// ========== Result record ==========

	/**
	 * Assembled context ready for the LLM tool loop.
	 */
	public record ContextResult(
		AVector<ACell> history,
		AVector<ACell> tools,
		Map<String, AString> configToolMap,
		AVector<ACell> caps,
		RequestContext capsCtx,
		AMap<AString, ACell> config,
		long bytesConsumed,
		long bytesRemaining
	) {}
}

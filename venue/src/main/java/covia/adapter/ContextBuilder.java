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
import convex.core.data.Hash;
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

	private static final AString DEFAULT_SYSTEM_PROMPT = Strings.create(
		"You are a helpful AI agent on the Covia platform. "
		+ "Use tools and grid operations to complete tasks. "
		+ "Give concise, clear and accurate responses.");

	/** Default tool operations — resolved at runtime via engine */
	static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("agent:create"),
		(ACell) Strings.create("agent:message"),
		(ACell) Strings.create("agent:request"),
		(ACell) Strings.create("asset:store"),
		(ACell) Strings.create("asset:get"),
		(ACell) Strings.create("asset:list"),
		(ACell) Strings.create("asset:content"),
		(ACell) Strings.create("asset:pin"),
		(ACell) Strings.create("grid:run"),
		(ACell) Strings.create("covia:read"),
		(ACell) Strings.create("covia:write"),
		(ACell) Strings.create("covia:delete"),
		(ACell) Strings.create("covia:append"),
		(ACell) Strings.create("covia:slice"),
		(ACell) Strings.create("covia:list"),
		(ACell) Strings.create("covia:inspect"),
		(ACell) Strings.create("covia:functions"),
		(ACell) Strings.create("covia:describe"),
		(ACell) Strings.create("schema:validate"),
		(ACell) Strings.create("schema:infer")
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

	public ContextBuilder(Engine engine, RequestContext ctx) {
		this(engine, ctx, DEFAULT_BUDGET);
	}

	public ContextBuilder(Engine engine, RequestContext ctx, long budget) {
		this.engine = engine;
		this.ctx = ctx;
		this.totalBudget = budget;
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
	 * Prepends system prompt if not already present in existing history.
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withSystemPrompt(AVector<ACell> existingHistory) {
		messages = existingHistory;
		if (messages == null) messages = Vectors.empty();

		if (messages.count() == 0 || !ROLE_SYSTEM.equals(RT.getIn(messages.get(0), K_ROLE))) {
			AString sysContent = getConfigValue(config, K_SYSTEM_PROMPT, null);
			if (sysContent == null) sysContent = DEFAULT_SYSTEM_PROMPT;
			ACell sysMsg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, sysContent);
			messages = (AVector<ACell>) Vectors.of(sysMsg).concat(messages);
			trackMessage(sysMsg);
		} else {
			trackMessage(messages.get(0));
		}
		// Track remaining history messages
		for (long i = 1; i < messages.count(); i++) {
			trackMessage(messages.get(i));
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
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withTools() {
		boolean useDefaults = config == null || !CVMBool.FALSE.equals(config.get(K_DEFAULT_TOOLS));
		configToolMap = new HashMap<>();

		AVector<ACell> baseTools = Vectors.empty();
		if (useDefaults) {
			baseTools = buildConfigTools(DEFAULT_TOOL_OPS, configToolMap);
		}

		if (config != null) {
			ACell toolsCell = config.get(K_TOOLS);
			if (toolsCell instanceof AVector<?> toolsVec) {
				baseTools = (AVector<ACell>) baseTools.concat(
					buildConfigTools((AVector<ACell>) toolsVec, configToolMap));
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

	// ========== Tool building (moved from LLMAgentAdapter) ==========

	/**
	 * Resolves config tools to LLM tool definitions.
	 */
	@SuppressWarnings("unchecked")
	AVector<ACell> buildConfigTools(AVector<ACell> toolsVec, Map<String, AString> toolMap) {
		AVector<ACell> result = Vectors.empty();
		for (long i = 0; i < toolsVec.count(); i++) {
			ACell entry = toolsVec.get(i);

			AString[] parsed = parseConfigToolEntry(entry);
			if (parsed == null) continue;

			AString operation = parsed[0];
			AString nameOverride = parsed[1];
			AString descOverride = parsed[2];

			Hash hash = engine.resolveOperation(operation);
			if (hash == null) {
				log.warn("Config tool: cannot resolve operation '{}'", operation);
				continue;
			}
			Asset asset = engine.getAsset(hash);
			if (asset == null) {
				log.warn("Config tool: asset not found for operation '{}'", operation);
				continue;
			}

			AString assetToolName = RT.ensureString(asset.meta().get(Fields.TOOL_NAME));
			String toolName = deriveToolName(nameOverride, assetToolName, operation);

			AString description = (descOverride != null)
				? descOverride
				: RT.ensureString(asset.meta().get(Fields.DESCRIPTION));

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

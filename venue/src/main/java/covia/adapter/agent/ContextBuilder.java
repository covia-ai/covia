package covia.adapter.agent;

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
import convex.core.data.type.Types;
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
 *     .withConfig(recordConfig)
 *     .withSystemPrompt()
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
	private static final AString K_SKILLS        = Strings.intern("skills");
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
		+ "These namespaces describe what may exist, not what you can currently do. "
		+ "Only claim or use capabilities backed by tools actually offered to you.\n"
		+ "\n"
		+ "User namespaces (scoped to current user):\n"
		+ "  w/  Workspace — persistent user data\n"
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

	/** Default tool operations — deliberately minimal: read-only situational
	 *  awareness (inspect the workspace and catalog, discover what exists).
	 *  Everything with side effects arrives via skills — {@code skill_load}
	 *  activates a skill's tool bundle mid-transition — or the agent's explicit
	 *  config {@code tools} allowlist. */
	static final AVector<ACell> DEFAULT_TOOL_OPS = (AVector<ACell>) Vectors.of(
		(ACell) Strings.create("v/ops/covia/read"),
		(ACell) Strings.create("v/ops/covia/list")
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

	/** The in-scope session id (hex), surfaced in the system prompt so the
	 *  agent can address its own conversation from deferred work. */
	private String sessionIdHex;

	/**
	 * Declares the in-scope session id, rendered into the system prompt's
	 * session-context line ({@code Session: <hex>}). Call before
	 * {@link #withSystemPrompt()}. Accepts a Blob or hex string cell; null /
	 * unrecognised values leave the line out (no session in scope).
	 */
	public ContextBuilder withSessionId(ACell sid) {
		if (sid instanceof convex.core.data.ABlob b && !(sid instanceof AString)) {
			this.sessionIdHex = b.toHexString();
		} else {
			AString s = RT.ensureString(sid);
			if (s != null) this.sessionIdHex = s.toString();
		}
		return this;
	}

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
	 * Sets the agent config. Must be called first. Config has a single home —
	 * {@code record.config}, written by the principal (#144); the runtime's
	 * {@code state} carries no configuration and none is merged from it.
	 */
	public ContextBuilder withConfig(AMap<AString, ACell> config) {
		this.config = config;
		return this;
	}

	/**
	 * Builds a fresh system message and prepends it to {@code messages}.
	 *
	 * <p>Composed of the agent's identity prompt (from {@code config.systemPrompt}
	 * or {@link #DEFAULT_IDENTITY_PROMPT}) plus session context (date, venue,
	 * model), the {@link #LATTICE_REFERENCE} cheat sheet when the agent has
	 * tools, and the caps section when caps are declared. Rebuilt every turn —
	 * updates to either source apply immediately, no freeze-on-first-turn caching.</p>
	 *
	 * <p>Idempotent: if {@code messages} already starts with a system message
	 * (e.g. from a previous {@code withSystemPrompt} call on this builder),
	 * it is dropped and replaced by the freshly composed one.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withSystemPrompt() {
		// If messages already has a system message at index 0, drop it —
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

		// Session context: venue identity and stable ids ONLY. The current
		// date is deliberately NOT here — the system prompt is the head of
		// every provider's cached prefix (OpenAI automatic prefix caching,
		// Anthropic cache_control on system+tools), so it must contain no
		// changing values. The date rides withCurrentDate() at the TAIL.
		sb.append("\n\nVenue: ").append(engine.getName());
		// Model name, if configured — helps the LLM self-calibrate
		AString model = getConfigValue(config, Strings.intern("model"), null);
		if (model != null) sb.append(". Model: ").append(model);
		// The conversation's session id, when one is in scope — the agent's
		// handle for REPORTING BACK into this conversation from deferred work:
		// a scheduled agent:request carrying this sessionId runs in this
		// session, so its turns land in the history the user reads.
		if (sessionIdHex != null) sb.append(". Session: ").append(sessionIdHex);
		sb.append('.');

		// Namespace literacy is useful even for a reasoning-only agent because
		// paths can arrive in user input or configured context. The reference
		// explicitly distinguishes addressability from actual tool capability.
		sb.append("\n\n").append(LATTICE_REFERENCE.toString());
		String capsSection = renderCapsForPrompt(
			RT.ensureVector(config != null ? config.get(K_CAPS) : null));
		if (capsSection != null) {
			sb.append("\n\n").append(capsSection);
		}
		AString sysContent = Strings.create(sb.toString());
		ACell sysMsg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT, sysContent);
		messages = (AVector<ACell>) Vectors.of(sysMsg).concat(messages);
		trackMessage(sysMsg);
		return this;
	}

	/**
	 * Appends a session frame stack as LLM messages.
	 *
	 * <p>For stacks of depth &gt; 1, ancestors are summarised via
	 * {@link covia.adapter.agent.GoalTreeContext#renderAncestors} (one system
	 * message summarising all parent frames). The active (deepest) frame's
	 * {@code conversation} entries are then appended:
	 * <ul>
	 *   <li>Compacted segments → system messages of the form
	 *       {@code [Compacted: N turns] summary}.</li>
	 *   <li>Live turns {@code {role, content, ts, source}} → LLM messages
	 *       {@code {role, content}} with content stringified. Caller provenance
	 *       is rendered consistently with live inbox messages; other framework
	 *       metadata such as {@code ts} and {@code source} is dropped.</li>
	 * </ul></p>
	 *
	 * <p>In the degenerate single-frame case (LLMAgentAdapter, GoalTreeAdapter
	 * pre-subgoal) this emits the root frame's conversation turns as flat
	 * LLM messages — equivalent to the pre-cutover {@code withSessionHistory}.</p>
	 *
	 * <p>If {@code frames} is null or empty, returns silently.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withFrameStack(AVector<ACell> frames) {
		if (frames == null || frames.count() == 0) return this;

		// Progressive ancestor compaction — the central mechanism that lets
		// goal-tree agents stay within budget no matter how deep the stack
		// goes. Each ancestor is rendered at a decreasing CellExplorer
		// budget (parent ~300B, grandparent ~150B, ...) into a single
		// system message. Don't simplify away — see GoalTreeContext
		// .renderAncestors and venue/docs/GOAL_TREE.md §"Context Assembly".
		// (single-frame case: no-op)
		if (frames.count() > 1) {
			AMap<AString, ACell> ancestorMsg =
				covia.adapter.agent.GoalTreeContext.renderAncestors(frames);
			if (ancestorMsg != null) {
				messages = messages.conj(ancestorMsg);
				trackMessage(ancestorMsg);
			}
		}

		// Active frame's conversation via GoalTreeContext — already handles
		// segment → system message conversion. We then normalise each entry
		// to {role, content} with stringified content (vendor APIs require
		// string content and ignore extra fields like ts/source).
		ACell activeCell = frames.get(frames.count() - 1);
		if (!(activeCell instanceof AMap)) return this;
		AVector<ACell> rendered = covia.adapter.agent.GoalTreeContext
			.renderConversationFor((AMap<AString, ACell>) activeCell, config);

		for (long i = 0; i < rendered.count(); i++) {
			ACell entry = rendered.get(i);
			AMap<AString, ACell> msg = renderMessageForContext(entry, null,
				(ctx != null) ? ctx.getCallerDID() : null);
			if (msg == null) continue;
			messages = messages.conj(msg);
			trackMessage(msg);
		}
		return this;
	}

	/**
	 * Resolves the operator-pinned context entries ({@code config.context})
	 * using ContextLoader with CellExplorer. The old {@code state.context}
	 * layer had no writer and is retired (#142) — dynamic context is the
	 * loads scope chain ({@link ContextChain}).
	 */
	public ContextBuilder withContextEntries() {
		ContextLoader loader = new ContextLoader(engine);

		// Set up CellExplorer with a reasonable per-entry budget
		int entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, getRemaining() / 20);
		loader.setCellExplorer(new CellExplorer(entryBudget));

		if (config != null) {
			AVector<ACell> configContext = contextVector(config.get(K_CONTEXT), "config.context");
			AVector<ACell> contextMsgs = loader.resolve(configContext, ctx);
			for (long i = 0; i < contextMsgs.count(); i++) {
				ACell msg = contextMsgs.get(i);
				messages = messages.conj(msg);
				trackMessage(msg);
			}
		}
		return this;
	}

	/**
	 * Coerces a {@code context} value to a vector of entries.
	 *
	 * <p>{@code null}/absent means "no context for this layer" and returns
	 * {@code null} (the layer is skipped — legitimately optional). Any other
	 * non-vector value is a <b>configuration error</b> and throws: a malformed
	 * {@code config.context} / {@code state.context} must fail loudly so the
	 * agent config gets fixed, rather than being silently dropped (which looks
	 * like "context isn't working" with no signal). This is distinct from an
	 * individual entry that can't be resolved (deleted asset, empty path),
	 * which remains fail-open and is skipped.</p>
	 */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> contextVector(ACell raw, String which) {
		if (raw == null) return null;
		if (raw instanceof AVector) return (AVector<ACell>) raw;
		throw new RuntimeException(which + " must be an array of context entries, got "
			+ Types.get(raw) + " — fix the agent config");
	}

	/** Preamble ahead of the skills index lines — tells the model what the
	 *  block is and how to act on it (see venue/docs/SKILLS.md §4.2). */
	private static final String SKILLS_PREAMBLE =
		"[Skills]\n"
		+ "Named skill packs you can load with skill_load({name: \"...\"}). Loading injects the\n"
		+ "skill's instructions into your context (persists across turns; unload with\n"
		+ "context_unload) and adds its tools to your palette.\n";

	/**
	 * Injects the skills index — one compact system message listing the
	 * skills discoverable from the agent's {@code config.skills} sources,
	 * with a {@code (loaded)} marker against skills already in effective
	 * context (see venue/docs/SKILLS.md §4). Resolved fresh each turn, like
	 * every other ephemeral section. No-op when the agent declares no skill
	 * sources or the sources yield nothing; a malformed {@code config.skills}
	 * throws (same rule as {@code config.context} — a configuration error
	 * must fail loudly, not vanish).
	 *
	 * @param effectiveLoads Effective loads for the {@code (loaded)} marker;
	 *        null when no loads tier is in scope
	 */
	public ContextBuilder withSkillsIndex(AMap<AString, ACell> effectiveLoads) {
		if (config == null) return this;
		AVector<ACell> sources = skillSources(config.get(K_SKILLS));
		if (sources.count() == 0) return this;

		String index = Skills.renderIndex(engine, ctx, sources, effectiveLoads);
		if (index == null) return this;                      // nothing anywhere → no block

		ACell msg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT,
			Strings.create(SKILLS_PREAMBLE + index));
		messages = messages.conj(msg);
		trackMessage(msg);
		return this;
	}

	/**
	 * Coerces a {@code config.skills} value to a vector of source ref strings.
	 * Absent → empty (no skills for this agent). A non-vector value or a
	 * non-string entry is a configuration error and throws — mirroring
	 * {@link #contextVector}'s malformed-shape rule.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> skillSources(ACell raw) {
		if (raw == null) return Vectors.empty();
		if (!(raw instanceof AVector)) {
			throw new RuntimeException("config.skills must be an array of skill source refs, got "
				+ Types.get(raw) + " — fix the agent config");
		}
		AVector<ACell> sources = (AVector<ACell>) raw;
		for (long i = 0; i < sources.count(); i++) {
			if (RT.ensureString(sources.get(i)) == null) {
				throw new RuntimeException("config.skills entries must be source ref strings, got "
					+ Types.get(sources.get(i)) + " — fix the agent config");
			}
		}
		return sources;
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
		java.util.Set<convex.core.data.Hash> seenSkillIds = new java.util.HashSet<>();

		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();

			int entryBudget = 500;
			ACell budgetCell = meta.get(Strings.intern("budget"));
			if (budgetCell instanceof convex.core.data.prim.CVMLong l) {
				entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, Math.min(l.longValue(), 10_000));
			}

			loader.setCellExplorer(new CellExplorer(entryBudget));
			result = (AVector<ACell>) result.concat(
				renderLoadEntry(loader, path, meta, seenSkillIds, ctx));
		}
		return result;
	}

	/**
	 * Resolves dynamically loaded paths from the loads scope chain.
	 * Each entry is resolved fresh using ContextLoader with per-entry CellExplorer budget.
	 * Data entries that exceed remaining budget or fail to resolve are silently
	 * skipped; skill entries fail VISIBLY (see {@link #renderLoadEntry}).
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withLoadedPaths(AMap<AString, ACell> loads) {
		return withLoadedPaths(loads, ctx);
	}

	/**
	 * Resolves dynamically loaded paths under an explicit authority context.
	 * Static config context may be operator-pinned, while model-selected loads
	 * must use the agent's capability-scoped context.
	 */
	public ContextBuilder withLoadedPaths(AMap<AString, ACell> loads,
			RequestContext resolutionCtx) {
		if (loads == null || loads.count() == 0) return this;

		ContextLoader loader = new ContextLoader(engine);
		java.util.Set<convex.core.data.Hash> seenSkillIds = new java.util.HashSet<>();

		for (var entry : loads.entrySet()) {
			AString path = entry.getKey();
			AMap<AString, ACell> meta = (AMap<AString, ACell>) entry.getValue();

			int entryBudget = 500;
			ACell budgetCell = meta.get(Strings.intern("budget"));
			if (budgetCell instanceof convex.core.data.prim.CVMLong l) {
				entryBudget = (int) Math.max(MIN_ENTRY_BUDGET, Math.min(l.longValue(), 10_000));
			}

			if (entryBudget > getRemaining()) {
				// Data loads skip silently under budget pressure; a skill the
				// agent loaded must not silently vanish — it changes behaviour.
				if (Skills.isSkillEntry(meta)) {
					ACell notice = Skills.skillErrorMessage(loadLabel(path, meta),
						"context budget exhausted — unload something or raise the budget");
					messages = messages.conj(notice);
					trackMessage(notice);
				}
				continue;
			}

			loader.setCellExplorer(new CellExplorer(entryBudget));
			AVector<ACell> rendered = renderLoadEntry(
				loader, path, meta, seenSkillIds, resolutionCtx);
			for (long i = 0; i < rendered.count(); i++) {
				ACell msg = rendered.get(i);
				messages = messages.conj(msg);
				trackMessage(msg);
			}
		}
		return this;
	}

	/**
	 * Renders one loads entry to its context messages. This is the single
	 * place that knows about entry KINDS — adapters just pass loads through,
	 * so the same assembly can host a variety of additions:
	 * <ul>
	 *   <li><b>Skill entries</b> ({@code skill: true} — SKILLS.md §6): the
	 *       skill re-resolves from the entry key and renders as
	 *       {@code [Skill: <name>]} + body verbatim, followed by the skill's
	 *       own context entries. Failures are <b>visible</b> — a skill the
	 *       agent loaded must not silently disappear.</li>
	 *   <li><b>Everything else</b>: the standard context-entry resolution of
	 *       the key (absent → skipped, errors → ContextLoader's visible
	 *       element).</li>
	 * </ul>
	 */
	private AVector<ACell> renderLoadEntry(ContextLoader loader, AString path,
			AMap<AString, ACell> meta, java.util.Set<convex.core.data.Hash> seenSkillIds,
			RequestContext resolutionCtx) {
		if (Skills.isSkillEntry(meta)) {
			try {
				Skills.ResolvedSkill skill = Skills.resolveRef(engine, resolutionCtx, path);
				// Content-identity dedup across the render pass: skills are
				// content-addressed, so the same skill loaded under two
				// addresses (or at two tiers) renders its body once. The
				// identity is LIVE (from this resolution — cell hashes are
				// memoised), accumulated transiently; nothing persisted.
				if (skill.id() != null && !seenSkillIds.add(skill.id())) {
					return Vectors.empty();
				}
				AVector<ACell> msgs = Vectors.of(
					Skills.renderSkillMessage(skill.name(), skill.displayBody()));
				if (skill.contextEntries().count() > 0) {
					msgs = msgs.concat(loader.resolve(skill.contextEntries(), resolutionCtx));
				}
				return msgs;
			} catch (RuntimeException e) {
				return Vectors.of(Skills.skillErrorMessage(loadLabel(path, meta),
					ContextLoader.rootMessage(e)));
			}
		}
		ACell msg = loader.resolveEntry(path, resolutionCtx);
		return (msg != null) ? Vectors.of((ACell) msg) : Vectors.empty();
	}

	/** An entry's display label: its {@code label} else its path. */
	private static String loadLabel(AString path, AMap<AString, ACell> meta) {
		AString label = RT.ensureString(meta.get(Strings.intern("label")));
		return (label != null) ? label.toString() : path.toString();
	}

	/**
	 * Resolves the tool declarations carried by loads entries into LLM tool
	 * definitions — the generic <b>"a loads entry may contribute tools to the
	 * palette"</b> rule. Any entry whose spec carries a {@code tools} vector
	 * of operation refs contributes (skills are the first producer — SKILLS.md
	 * §5.3 — but the mechanism is kind-agnostic). Definitions resolve fresh
	 * (same liveness as {@code config.tools}), deduplicated against
	 * {@code excludeNames} and each other; {@code toolMap} gains the dispatch
	 * routes of the returned defs.
	 */
	@SuppressWarnings("unchecked")
	public static AVector<ACell> loadsToolDefs(Engine engine, RequestContext ctx,
			AMap<AString, ACell> effectiveLoads, java.util.Set<String> excludeNames,
			Map<String, AString> toolMap) {
		AVector<ACell> added = Vectors.empty();
		if (effectiveLoads == null || effectiveLoads.count() == 0) return added;

		java.util.Set<String> names = new java.util.HashSet<>();
		if (excludeNames != null) names.addAll(excludeNames);

		ContextBuilder resolver = new ContextBuilder(engine, ctx);
		for (var entry : effectiveLoads.entrySet()) {
			ACell spec = entry.getValue();
			if (!(spec instanceof AMap)) continue;
			AVector<ACell> ops = RT.ensureVector(((AMap<AString, ACell>) spec).get(K_TOOLS));
			if (ops == null || ops.count() == 0) continue;
			Map<String, AString> newMap = new HashMap<>();
			AVector<ACell> defs = resolver.buildConfigTools(ops, newMap);
			for (long i = 0; i < defs.count(); i++) {
				ACell def = defs.get(i);
				AString n = RT.ensureString(RT.getIn(def, K_NAME));
				if (n == null || !names.add(n.toString())) continue;
				added = added.conj(def);
				AString route = newMap.get(n.toString());
				if (route != null) toolMap.put(n.toString(), route);
			}
		}
		return added;
	}

	/**
	 * Appends the current date as a small tail message. Kept OUT of the system
	 * prompt so the cacheable prefix (system + reference + history) contains no
	 * changing values — this message busts only itself, once a day.
	 */
	public ContextBuilder withCurrentDate() {
		ACell msg = Maps.of(K_ROLE, ROLE_SYSTEM, K_CONTENT,
			Strings.create("Current date: " + java.time.LocalDate.now() + "."));
		messages = messages.conj(msg);
		trackMessage(msg);
		return this;
	}

	/**
	 * Appends a compact context map showing budget status and loaded paths.
	 *
	 * <p><b>Call this LAST among message sections</b>: the budget numbers
	 * change on every build, so anything after this message is uncacheable by
	 * prefix-cached providers. At the tail it busts only itself — and reports
	 * the most accurate totals.</p>
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
				sb.append(" [").append(budget).append("B]");
				if (Skills.isSkillEntry(meta)) sb.append(" (skill)");
				sb.append('\n');
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
	 * Appends each inbox message as a user turn. Provenance for the current
	 * authenticated caller is redundant and omitted; a genuinely different
	 * sender is identified with a neutral authenticated-sender label.
	 */
	public ContextBuilder withInboxMessages(AVector<ACell> inboxMessages) {
		if (inboxMessages == null) return this;
		for (long i = 0; i < inboxMessages.count(); i++) {
			AMap<AString, ACell> userMsg = renderMessageForContext(
				inboxMessages.get(i), ROLE_USER,
				(ctx != null) ? ctx.getCallerDID() : null);
			if (userMsg == null) continue;
			messages = messages.conj(userMsg);
			trackMessage(userMsg);
		}
		return this;
	}

	/**
	 * Converts one stored turn or live inbox envelope into the provider-facing
	 * message shape. This is the single attribution path for both sources.
	 *
	 * @param value stored turn, inbox envelope, or raw message string
	 * @param defaultRole role used when {@code value} has none; null requires one
	 * @param currentCaller authenticated principal in the current run context
	 * @return provider-facing message, or null when no role can be established
	 */
	@SuppressWarnings("unchecked")
	static AMap<AString, ACell> renderMessageForContext(ACell value,
			AString defaultRole, AString currentCaller) {
		AString role = defaultRole;
		AString caller = null;
		ACell content = value;
		AMap<AString, ACell> source = null;

		if (value instanceof AMap<?, ?> raw) {
			source = (AMap<AString, ACell>) raw;
			AString sourceRole = RT.ensureString(source.get(K_ROLE));
			if (sourceRole != null) role = sourceRole;
			caller = RT.ensureString(source.get(Fields.CALLER));
			content = source.get(Fields.MESSAGE);
			if (content == null) content = source.get(K_CONTENT);
			// Preserve the old diagnostic fallback for malformed inbox envelopes.
			if (content == null && defaultRole != null) content = source;
		}

		if (role == null) return null;
		AString contentStr;
		if (content instanceof AString s) {
			contentStr = s;
		} else if (content == null) {
			contentStr = Strings.EMPTY;
		} else {
			// JSON, never EDN: CVM map toString() output is hard for models to read.
			contentStr = convex.core.util.JSON.print(content);
		}

		if (ROLE_USER.equals(role) && caller != null && !caller.equals(currentCaller)) {
			contentStr = Strings.create("[Authenticated sender: " + caller + "]\n" + contentStr);
		}

		AMap<AString, ACell> message = Maps.of(
			K_ROLE, role,
			K_CONTENT, contentStr);
		if (source == null) return message;

		// Keep provider-significant tool fields while dropping framework-only
		// metadata. Attribution has already been folded into user content above.
		for (AString key : java.util.List.of(
				Strings.intern("toolCalls"), Strings.intern("id"),
				K_NAME, Fields.STRUCTURED_CONTENT)) {
			ACell fieldValue = source.get(key);
			if (fieldValue != null) message = message.assoc(key, fieldValue);
		}
		return message;
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
	 * Builds tool list from config and extracts caps.
	 *
	 * <p>By default the agent is advertised only the tools it declares in its
	 * {@code tools} array (strict allowlist). Setting {@code defaultTools: true}
	 * in the config opts into the additional {@link #DEFAULT_TOOL_OPS} pack.</p>
	 *
	 * <p>The default-tool resolution is cached per Engine via
	 * {@link #DEFAULT_TOOL_CACHE}: the first call per engine builds the
	 * default tool definitions; subsequent calls reuse them. Per-config
	 * tools are still rebuilt fresh each call since they may include
	 * user-scoped {@code /o/<name>} references.</p>
	 */
	@SuppressWarnings("unchecked")
	public ContextBuilder withTools() {
		// Strict allowlist by default (#92): an agent is advertised exactly the
		// tools it declares, unless it explicitly opts into the default pack
		// with defaultTools: true. This is fail-safe — a newly-authored agent
		// does not silently receive ops it never declared. The pack itself is
		// deliberately minimal (read-only workspace access) — capability tools
		// arrive via skills or the config tools allowlist.
		boolean useDefaults = config != null && CVMBool.TRUE.equals(config.get(K_DEFAULT_TOOLS));
		configToolMap = new HashMap<>();

		AVector<ACell> baseTools = Vectors.empty();
		if (useDefaults) {
			// Atomic population: a plain get-then-put let concurrent first-callers
			// on the same Engine each build and store a different entry, so the
			// "stable cached instance" contract broke under parallel tests
			// (ContextBuilderTest.testDefaultToolsCached). computeIfAbsent builds
			// once per Engine and every caller sees the same instance.
			DefaultToolsCacheEntry cached = DEFAULT_TOOL_CACHE.computeIfAbsent(engine, e -> {
				Map<String, AString> freshMap = new HashMap<>();
				AVector<ACell> freshTools = buildConfigTools(DEFAULT_TOOL_OPS, freshMap);
				return new DefaultToolsCacheEntry(freshTools, freshMap);
			});
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

			Asset asset;
			try {
				asset = engine.resolveAsset(operation, ctx);
			} catch (covia.exception.RemoteFetchException e) {
				// A tool whose definition lives on an unreachable remote venue
				// must not fail the whole tool list (and the agent turn) — skip
				// it visibly. Other resolution errors still propagate (#174).
				log.warn("Config tool: skipping '{}' — remote fetch failed: {}", operation, e.getMessage());
				continue;
			}
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
	 * Best-effort list of config {@code tools} operations that resolve to nothing
	 * on this venue (#205). Mirrors {@link #buildConfigTools}'s resolution so the
	 * check can't drift from actual behaviour: harness pseudo-tools are skipped,
	 * and a tool whose definition lives on an unreachable remote venue is <b>not</b>
	 * reported (transient, not a config error). Any other resolution error is also
	 * skipped — this is an advisory, so it only reports the definitive
	 * "resolves to null" case (the silent-drop that {@code buildConfigTools} logs).
	 * Returns the unresolved operation strings in order; empty when all resolve.
	 *
	 * @param skipToolNames bare harness names to ignore (see
	 *        {@link AbstractLLMAdapter#allHarnessToolNames()})
	 */
	public static java.util.List<String> unresolvableConfigTools(
			Engine engine, RequestContext ctx, AVector<ACell> toolsVec,
			java.util.Set<String> skipToolNames) {
		java.util.List<String> unresolved = new java.util.ArrayList<>();
		if (toolsVec == null) return unresolved;
		for (long i = 0; i < toolsVec.count(); i++) {
			AString[] parsed = parseConfigToolEntry(toolsVec.get(i));
			if (parsed == null) continue;
			String operation = parsed[0].toString();
			if (skipToolNames.contains(operation)) continue;   // harness pseudo-tool
			Asset asset;
			try {
				asset = engine.resolveAsset(parsed[0], ctx);
			} catch (RuntimeException e) {
				continue;   // remote unreachable / ambiguous — don't over-warn at create
			}
			if (asset == null) unresolved.add(operation);
		}
		return unresolved;
	}

	/**
	 * Parses a config tool entry (string or map) into its components.
	 *
	 * @return Array of [operation, nameOverride, descOverride], or null if invalid
	 */
	@SuppressWarnings("unchecked")
	public
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
	public static String deriveToolName(AString nameOverride, AString assetToolName, AString operation) {
		if (nameOverride != null) return nameOverride.toString();
		if (assetToolName != null) return assetToolName.toString();
		return operation.toString().replace(':', '_').replace('/', '_');
	}

	/**
	 * Builds a tool definition map from resolved components.
	 */
	@SuppressWarnings("unchecked")
	public
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

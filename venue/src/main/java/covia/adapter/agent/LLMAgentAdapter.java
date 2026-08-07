package covia.adapter.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.json.schema.JsonSchema;
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
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.AgentState;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * LLM-backed transition function for agents (level 2).
 *
 * <p>Invoked by the agent run loop as a transition operation ({@code llmagent:chat}).
 * Maintains conversation history in the agent's {@code state} field and delegates
 * LLM calls to a level 3 grid operation (e.g. {@code langchain:openai}).</p>
 *
 * <p>This adapter has no LLM library dependencies — it works entirely with
 * structured message maps and invokes level 3 via the grid operation dispatch.</p>
 *
 * <h3>Tool palette</h3>
 * <p>Agents are advertised exactly the tools they declare in config
 * {@code tools} (strict allowlist, #92). {@code defaultTools: true} adds the
 * deliberately minimal read-only pack in
 * {@link ContextBuilder#DEFAULT_TOOL_OPS}; capability tools arrive via skills
 * ({@code skill_load}). Task tools ({@code complete_task}, {@code fail_task})
 * are added dynamically when tasks are pending.</p>
 *
 * <p>Additional tools can be configured via {@code tools} in the agent's config.
 * Each entry is a string (operation name) or map with {@code operation} plus optional
 * {@code name} and {@code description} overrides. Config tools are resolved from
 * adapter functions or grid operations and flattened as direct tools for the LLM.</p>
 *
 * <h3>Message format</h3>
 * <p>All messages in history use a common map format:</p>
 * <ul>
 *   <li>{@code {role: "system"|"user", content: "..."}}</li>
 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments}]}}</li>
 *   <li>{@code {role: "tool", id: "...", name: "...", content: "..."}}</li>
 * </ul>
 *
 * <h3>Tool call loop</h3>
 * <p>When level 3 returns an assistant message with {@code toolCalls}, level 2
 * executes each tool as a grid operation, appends tool result messages, and
 * calls level 3 again. This loops until the LLM returns a text response
 * (no tool calls) or the tool-call iteration limit is reached (venue
 * config {@code maxToolIterations}, default 30, overridable per agent via
 * {@code config.maxToolIterations}).</p>
 *
 * <h3>State structure</h3>
 * <pre>{@code
 * { "config": {
 *     "llmOperation": "v/ops/langchain/openai",
 *     "model": "gpt-4o-mini",
 *     "systemPrompt": "You are...",
 *     "tools": [{name, description, parameters}]
 *   },
 *   "history": [
 *     { "role": "system",    "content": "You are..." },
 *     { "role": "user",      "content": "Hello" },
 *     { "role": "assistant", "content": "Hi there!" }
 *   ]
 * }}</pre>
 */
public class LLMAgentAdapter extends AbstractLLMAdapter {

	private static final Logger log = LoggerFactory.getLogger(LLMAgentAdapter.class);

	// State field keys

	// Config keys specific to this adapter (parent provides K_CONFIG, K_LLM_OPERATION,
	// K_MODEL, K_SYSTEM_PROMPT, K_URL, K_API_KEY, K_TOOLS, K_RESPONSE_FORMAT,
	// K_CONTEXT, K_CAPS, K_TOOL_CALL_TIMEOUT_MS).
	private static final AString K_DEFAULT_TOOLS   = Strings.intern("defaultTools");

	// Built-in tool names (only task tools remain as built-ins)
	private static final String TOOL_COMPLETE_TASK = "complete_task";
	private static final String TOOL_FAIL_TASK     = "fail_task";

	// ========== Default tool definitions ==========
	// MCP-style: {name, description, parameters: {type: "object", properties: {...}, required: [...]}}

	private static final AMap<AString, ACell> TOOL_DEF_COMPLETE_TASK = Maps.of(
		K_NAME, Strings.create(TOOL_COMPLETE_TASK),
		K_DESCRIPTION, Strings.create(
			"Deliver the final result for the in-scope task and end it. "
			+ "This is TERMINAL: `result` is the only channel back to the caller — "
			+ "any chat text you write on this turn is NOT seen by them. "
			+ "Only call this once you have the actual answer to deliver. "
			+ "The agent and task are determined from the current request context — you do not pass an id."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.RESULT, Maps.of(
					K_DESCRIPTION, Strings.create("The result to return to the requester. Any JSON value — string, object, array, etc. Required: omitting this delivers null to the caller."))
			),
			K_REQUIRED, Vectors.of(Strings.create("result"))
		)
	);

	private static final AMap<AString, ACell> TOOL_DEF_FAIL_TASK = Maps.of(
		K_NAME, Strings.create(TOOL_FAIL_TASK),
		K_DESCRIPTION, Strings.create(
			"Reject or fail the in-scope task. Call this when you cannot fulfil the request — "
			+ "e.g. the task is outside your capabilities or the input is invalid. "
			+ "The agent and task are determined from the current request context — you do not pass an id."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.ERROR, Maps.of(
					K_TYPE, Strings.create("string"),
					K_DESCRIPTION, Strings.create("Human-readable explanation of why the task cannot be completed"))
			),
			K_REQUIRED, Vectors.of(Strings.create("error"))
		)
	);

	// Built-in context tool names
	private static final String TOOL_CONTEXT_LOAD   = "context_load";
	private static final String TOOL_CONTEXT_UNLOAD = "context_unload";
	private static final String TOOL_SKILL_LOAD     = "skill_load";

	/** Harness pseudo-tools this runtime provides — intercepted by the adapter,
	 *  never dispatched as operations (see {@link AbstractLLMAdapter#dispatchTool}). */
	static final java.util.Set<String> HARNESS_TOOL_NAMES = java.util.Set.of(
		TOOL_COMPLETE_TASK, TOOL_FAIL_TASK, TOOL_CONTEXT_LOAD, TOOL_CONTEXT_UNLOAD,
		TOOL_SKILL_LOAD);

	private static final AMap<AString, ACell> TOOL_DEF_CONTEXT_LOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_LOAD),
		K_DESCRIPTION, Strings.create(
			"Add a lattice path to this conversation's loaded context. "
			+ "The path is resolved fresh and injected as a system message on the "
			+ "next model invocation, including within the current tool loop. "
			+ "Scoped to the current session — other conversations are unaffected. "
			+ "Use for reference material you need across multiple turns. "
			+ "For one-shot reads, use inspect instead when available."),
		K_PARAMETERS, CONTEXT_LOAD_PARAMS);

	private static final AMap<AString, ACell> TOOL_DEF_CONTEXT_UNLOAD = Maps.of(
		K_NAME, Strings.create(TOOL_CONTEXT_UNLOAD),
		K_DESCRIPTION, Strings.create(
			"Remove a path from this conversation's loaded context, freeing its "
			+ "budget. Also hides an operator-pinned load (from config.loads) for "
			+ "this conversation only — the pin itself is untouched and other "
			+ "conversations still see it."),
		K_PARAMETERS, CONTEXT_UNLOAD_PARAMS);

	/** Context tools — always available to agents */
	private static final AVector<ACell> CONTEXT_TOOLS = (AVector<ACell>) Vectors.of(
		(ACell) TOOL_DEF_CONTEXT_LOAD,
		(ACell) TOOL_DEF_CONTEXT_UNLOAD
	);

	/** Offered only when the agent declares skill sources ({@code config.skills}).
	 *  The adapter holds no skills semantics — loading delegates to
	 *  {@link Skills#load} and rendering/activation to the context assembly. */
	private static final AMap<AString, ACell> TOOL_DEF_SKILL_LOAD = Maps.of(
		K_NAME, Strings.create(TOOL_SKILL_LOAD),
		K_DESCRIPTION, Strings.create(
			"Load a skill from the [Skills] index by name (or any skill by direct ref). "
			+ "The result includes the skill's full instructions for immediate use; they "
			+ "also stay in your context each turn until you context_unload the skill's "
			+ "path. The skill's tools join your palette from your next step."),
		K_PARAMETERS, SKILL_LOAD_PARAMS);

	/** Task tools only available when there are outstanding tasks */
	private static final AVector<ACell> TASK_TOOLS = (AVector<ACell>) Vectors.of(
		(ACell) TOOL_DEF_COMPLETE_TASK,
		(ACell) TOOL_DEF_FAIL_TASK
	);

	@Override
	public String getName() {
		return "llmagent";
	}

	@Override
	public String getDescription() {
		return "LLM-backed transition function for agents. Maintains conversation "
			+ "history in agent state, processes inbox messages as user turns, and "
			+ "invokes a level 3 grid operation for LLM calls. Supports tool call "
			+ "loops: when the LLM requests tool calls, executes them as grid "
			+ "operations and feeds results back until a text response is produced. "
			+ "Built-in tools: complete_task, fail_task (added dynamically when "
			+ "tasks are pending). All other tools dispatch via the grid.";
	}

	@Override
	protected void installAssets() {
		installAsset("llmagent/chat", "/adapters/llmagent/chat.json");
	}

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		return CompletableFuture.supplyAsync(() -> processChat(ctx, input), VIRTUAL_EXECUTOR);
	}

	/**
	 * Builds the L3 input for {@code agent:context} inspection. Same context
	 * pipeline as {@link #processChat}'s first iteration — system prompt,
	 * context entries, tool palette — minus the actual LLM invocation. Appends
	 * the optional {@code taskInput} as a user goal message.
	 */
	@Override
	protected AMap<AString, ACell> buildInspectionInput(
			AMap<AString, ACell> recordConfig, ACell state, ACell taskInput,
			AMap<AString, ACell> session, RequestContext ctx) {
		// Same scope-chain view as processChat (agent tier + session tier), so
		// the inspected skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.declaredLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");
		ACell sessLoads = RT.getIn(session, Fields.LOADS);
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> sessionTier = (sessLoads instanceof AMap)
			? (AMap<AString, ACell>) sessLoads : null;
		AMap<AString, ACell> effectiveLoads = ContextChain.effective(configLoads, sessionTier);

		ContextBuilder builder = new ContextBuilder(engine, ctx)
			.withConfig(recordConfig)
			.withSessionId(RT.getIn(session, Fields.ID))
			.withSystemPrompt()
			.withContextEntries()
			.withSkillsIndex(effectiveLoads)
			.withLoadedPaths(effectiveLoads);
		// Session in scope: render its conversation exactly as a live
		// transition would (same withFrameStack step as processChat), so the
		// inspected context includes prior turns and tool-failure diagnostics.
		AVector<ACell> frames = sessionFramesOf(session);
		if (frames != null && frames.count() > 0) {
			builder = builder.withFrameStack(frames);
		}
		ContextBuilder.ContextResult context = builder
			.withCurrentDate()
			.withContextMap(effectiveLoads)
			.withTools()
			.build();

		AVector<ACell> history = context.history();
		if (taskInput != null) {
			ACell goalMsg = Maps.of(K_ROLE, ROLE_USER, K_CONTENT,
				Strings.create(taskInput.toString()));
			history = history.conj(goalMsg);
		}

		// Mirror the live llmagent first iteration, including runtime-owned
		// context tools, optional skill_load, and tools contributed by loads.
		// ContextBuilder only resolves catalog operations; harness tools live here.
		AVector<ACell> tools = (AVector<ACell>) CONTEXT_TOOLS.concat(context.tools());
		if (Skills.sourcesOf(context.config()).count() > 0) {
			tools = (AVector<ACell>) Vectors.of((ACell) TOOL_DEF_SKILL_LOAD).concat(tools);
		}
		Map<String, AString> ignoredRoutes = new java.util.HashMap<>();
		AVector<ACell> loadTools = ContextBuilder.loadsToolDefs(engine, ctx,
			effectiveLoads, fixedToolNames(tools), ignoredRoutes);
		tools = (AVector<ACell>) tools.concat(loadTools);

		return buildL3Input(context.config(), history, tools);
	}

	/**
	 * Core transition function logic.
	 *
	 * <p>Builds conversation history, invokes level 3 (with tool call loop),
	 * and returns the updated state.</p>
	 *
	 * @param ctx Request context (caller identity for level 3 invocation)
	 * @param input Transition input: { agentId, state, tasks, pending, messages, config, newInput, session? }
	 * @return Transition output: { state, response | error }
	 */
	@SuppressWarnings("unchecked")
	ACell processChat(RequestContext ctx, ACell input) {
		// Cycle-scoped token tally (#217): every invokeLevel3 below adds its
		// provider-reported usage; drained into the transition output at the
		// end. Thread-confined — see AbstractLLMAdapter.TOKEN_TALLY.
		beginTokenTally();
		AString agentId = RT.ensureString(RT.getIn(input, Fields.AGENT_ID));
		ACell state = RT.getIn(input, AgentState.KEY_STATE);
		// S3c: prefer session.pending over agent-level messages when a session
		// is in scope. Both carry the same envelopes (S3b dual-write); reading
		// both would duplicate. effectiveMessages picks the right one.
		AVector<ACell> messages = AgentAdapter.effectiveMessages(input);
		AVector<ACell> tasks = (AVector<ACell>) RT.getIn(input, Fields.TASKS);
		AVector<ACell> pending = (AVector<ACell>) RT.getIn(input, Fields.PENDING);

		@SuppressWarnings("unchecked")
		AMap<AString, ACell> recordConfig = (RT.getIn(input, AgentState.KEY_CONFIG) instanceof AMap m) ? m : null;

		// Determine if there is real input for the agent
		boolean hasInput = (messages != null && messages.count() > 0)
			|| (tasks != null && tasks.count() > 0)
			|| (pending != null && pending.count() > 0);

		// Context scope chain (#142): agent tier (config.loads, operator-pinned)
		// → session tier (sessions.<sid>.loads, runtime-managed). The session is
		// the innermost tier for this runtime; a cycle with no session in scope
		// has no writable tier and context_load/unload fail diagnosably.
		boolean sessionInScope = RT.getIn(input, Fields.SESSION) != null;
		AMap<AString, ACell> configLoads = ContextChain.declaredLoads(
			RT.getIn(recordConfig, Fields.LOADS), "config.loads");
		AMap<AString, ACell> sessionTier = ContextChain.sessionLoads(input);
		AMap<AString, ACell> effectiveLoads = ContextChain.effective(configLoads, sessionTier);

		// Build per-turn LLM context. Ephemeral context model:
		// (transcript model): system prompt + context entries + loads +
		// [Context Map] are rebuilt FRESH every turn and never persisted.
		// Only the persistent history (session frames) carries forward
		// via withFrameStack.
		//
		// Order matters: ephemeral background → history → this turn's
		// pending/inbox → empty signal. System prompt goes first for
		// primacy, new input goes last for recency.
		AVector<ACell> sessionFrames = AgentAdapter.sessionFrames(input);
		AVector<ACell> configuredCaps = RT.ensureVector(
			recordConfig != null ? recordConfig.get(Strings.intern("caps")) : null);
		RequestContext loadCtx = ((configuredCaps != null)
			? ctx.withCaps(configuredCaps) : ctx).withAgentId(agentId);
		ChatContext chatContext = new ChatContext(
			recordConfig,
			RT.getIn(input, Fields.SESSION, Fields.ID),
			sessionFrames,
			pending,
			messages,
			hasInput);
		ContextBuilder builder = buildChatContext(chatContext, effectiveLoads, ctx, loadCtx)
			.withTools();
		ContextBuilder.ContextResult context = builder.build();

		// Safety valve — prunes the SESSION tier only (the agent tier is
		// operator-pinned and never pruned; #142).
		AMap<AString, ACell> activeSessionTier = builder.applySafetyValve(sessionTier);

		AVector<ACell> llmMessages = context.history();
		AVector<ACell> baseTools = context.tools();
		Map<String, AString> configToolMap = context.configToolMap();
		AMap<AString, ACell> config = context.config();
		// Add agent scope to the capability context — all tool calls carry the agentId
		// so adapters (e.g. CoviaAdapter) can resolve n/ paths to agent-private workspace.
		// The agent's config caps already ride on capsCtx (ContextBuilder.capsCtx).
		RequestContext capsCtx = context.capsCtx().withAgentId(agentId);

		// Extract LLM operation from merged config
		AString llmOperation = getLLMOperation(config);

		// Task context is built dynamically per tool-loop iteration (not baked into
		// history) so the LLM only sees outstanding tasks, not already-resolved ones.

		// Offer skill_load only when the agent declares skill sources. The
		// sources ride on the tool context as an opaque vector — all skills
		// semantics live in Skills / the context assembly, not this adapter.
		AVector<ACell> skillSources = Skills.sourcesOf(config);
		if (skillSources.count() > 0) {
			baseTools = (AVector<ACell>) Vectors.of((ACell) TOOL_DEF_SKILL_LOAD).concat(baseTools);
		}

		// Create tool context for built-in tool execution. Its loads slot is
		// the SESSION tier (the innermost writable tier for this runtime);
		// the agent tier rides along read-only for unload masking decisions.
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(config);
		ToolContext toolCtx = new ToolContext(agentId, capsCtx, tasks, pending, configToolMap,
			activeSessionTier, toolCallTimeoutMs);
		toolCtx.outerLoads = configLoads;
		toolCtx.sessionInScope = sessionInScope;
		toolCtx.skillSources = skillSources;
		toolCtx.fixedToolNames = fixedToolNames(baseTools);

		// Invoke level 3 with tool call loop — returns all messages to append
		// ctx (uncapped) for the L3 LLM call; capsCtx flows through toolCtx for tool dispatch
		// The first history was rendered from sessionTier. Normally the safety
		// valve returns that same immutable map and toolCtx starts with an
		// equivalent empty map when no tier exists. If it pruned, retain the old
		// key so the loop detects the mismatch and refreshes before inference.
		AMap<AString, ACell> renderedLoadsKey =
			(activeSessionTier == sessionTier) ? toolCtx.loads : sessionTier;
		AVector<ACell> newMessages = invokeWithToolLoop(
			llmOperation, config, llmMessages, baseTools, ctx, toolCtx,
			chatContext, renderedLoadsKey);

		// Filter out empty assistant messages (e.g. when LLM produces only <think> tags)
		// to avoid polluting the transcript with useless entries
		AVector<ACell> newMessagesFiltered = Vectors.empty();
		for (long i = 0; i < newMessages.count(); i++) {
			ACell msg = newMessages.get(i);
			if (ROLE_ASSISTANT.equals(RT.getIn(msg, K_ROLE))) {
				AString content = RT.ensureString(RT.getIn(msg, K_CONTENT));
				boolean hasContent = content != null && content.count() > 0;
				boolean hasToolCalls = RT.getIn(msg, K_TOOL_CALLS) instanceof AVector<?> v && v.count() > 0;
				if (!hasContent && !hasToolCalls) continue;
			}
			newMessagesFiltered = newMessagesFiltered.conj(msg);
		}

		// Extract text content from the final assistant message
		ACell lastMsg = newMessages.get(newMessages.count() - 1);
		AString contentText = RT.ensureString(RT.getIn(lastMsg, K_CONTENT));
		String responseText = (contentText != null) ? contentText.toString() : "";

		// Session.history is the sole conversation record and loads live on the
		// session tier (#142) — agent-level state carries nothing for this
		// runtime (config's single home is record.config, #144).
		AMap<AString, ACell> newState = Maps.empty();
		// Lean transition output: emit {state, response | error}. Task
		// completion (if any) is signalled to the framework by the venue op
		// invoked from the complete_task / fail_task tool wrappers, which
		// parks an envelope in deferredCompletions; the run loop drains
		// that map AFTER mergeRunResult to build the cycle's TASK_RESULTS.
		//
		// Default response is the assistant's chat text. If the LLM called
		// complete_task with structured output, that output overrides the
		// chat text in the timeline result (it's the authoritative task
		// answer). If the LLM called fail_task, the error replaces the
		// response entirely.
		AMap<AString, ACell> output = Maps.of(
			AgentState.KEY_STATE, newState,
			Fields.RESPONSE, Strings.create(responseText));
		// Preserve the non-terminal assistant/tool exchange for audit. The
		// framework appends these after the cycle's user input and before the
		// final response, so it retains chronological order without duplicating
		// the terminal assistant turn it already materialises from `response`.
		if (newMessagesFiltered.count() > 1) {
			output = output.assoc(Fields.TURNS,
				newMessagesFiltered.slice(0, newMessagesFiltered.count() - 1));
		}
		// Session-tier loads (post valve + this cycle's context_load/unload
		// mutations, tombstones included) — the framework writes them back to
		// sessions.<sid>.loads inside mergeRunResult's CAS (#142).
		if (sessionInScope) {
			output = output.assoc(Fields.LOADS, toolCtx.getLoads());
		}

		// Tool-failure diagnostics — the framework persists them to the
		// timeline entry and records them as system turns in the session
		// conversation, so denials/failures stay observable after the
		// cycle (#211).
		if (toolCtx.toolFailures.count() > 0) {
			output = output.assoc(Fields.TOOL_FAILURES, toolCtx.toolFailures);
		}

		if (toolCtx.taskResults != null && toolCtx.taskResults.count() > 0) {
			// One-task-per-cycle: take the single entry
			var entry = toolCtx.taskResults.entrySet().iterator().next();
			ACell taskResult = entry.getValue();
			AString status = RT.ensureString(RT.getIn(taskResult, Fields.STATUS));
			if (Status.FAILED.equals(status)) {
				ACell err = RT.getIn(taskResult, Fields.ERROR);
				if (err != null) output = output.assoc(Fields.ERROR, err)
					.dissoc(Fields.RESPONSE);
			} else {
				ACell taskOutput = RT.getIn(taskResult, Fields.OUTPUT);
				if (taskOutput != null) {
					output = output.assoc(Fields.RESPONSE, taskOutput);
				}
			}
		}

		// Cycle token totals (#217) — measured only; absent means the
		// provider reported nothing, never zero.
		AMap<AString, ACell> cycleTokens = endTokenTally();
		if (cycleTokens != null) {
			output = output.assoc(Fields.TOKENS, cycleTokens);
		}
		return output;
	}

	/**
	 * Immutable inputs used to rebuild the ephemeral portion of one chat
	 * transition. Loaded paths are deliberately absent: the tool loop supplies
	 * the current effective loads on every refresh.
	 */
	private record ChatContext(
		AMap<AString, ACell> config,
		ACell sessionId,
		AVector<ACell> sessionFrames,
		AVector<ACell> pending,
		AVector<ACell> messages,
		boolean hasInput) {}

	/**
	 * Builds the provider-facing history for the current load scope. Keeping
	 * this assembly in one place ensures the first inference and a mid-loop
	 * context_load/context_unload refresh have identical ordering, budgets,
	 * capability-scoped resolution, skill markers, and context-map reporting.
	 */
	private ContextBuilder buildChatContext(
			ChatContext chat,
			AMap<AString, ACell> effectiveLoads,
			RequestContext ctx,
			RequestContext loadCtx) {
		return new ContextBuilder(engine, ctx)
			.withConfig(chat.config())
			.withSessionId(chat.sessionId())
			.withSystemPrompt()                        // stable — cached prefix head
			.withContextEntries()                      // stable config.context
			.withSkillsIndex(effectiveLoads)           // refresh loaded markers
			.withLoadedPaths(effectiveLoads, loadCtx)  // agent-capability authority
			.withFrameStack(chat.sessionFrames())      // persistent conversation
			.withPendingResults(chat.pending())        // this transition
			.withInboxMessages(chat.messages())        // current user input
			.withEmptyStateSignal(chat.hasInput())
			.withCurrentDate()                         // volatile tail
			.withContextMap(effectiveLoads);           // always last
	}

	/**
	 * Invokes level 3 with a tool call loop.
	 *
	 * <p>Calls the LLM operation. If the response contains {@code toolCalls},
	 * executes each tool, appends tool result messages, and calls the LLM again.
	 * Repeats until a text-only response or the iteration limit.</p>
	 *
	 * <p>Built-in tools (complete_task, fail_task, grid_run, grid_invoke,
	 * message_agent) are intercepted and handled locally. All other tool names
	 * are dispatched as grid operations.</p>
	 *
	 * @return Vector of new messages to append to history (includes any tool call
	 *         assistant messages, tool result messages, and the final text response)
	 */
	@SuppressWarnings("unchecked")
	private AVector<ACell> invokeWithToolLoop(
			AString llmOperation, AMap<AString, ACell> config,
			AVector<ACell> history, AVector<ACell> baseTools, RequestContext ctx,
			ToolContext toolCtx, ChatContext chatContext,
			AMap<AString, ACell> renderedLoadsKey) {

		AVector<ACell> newMessages = Vectors.empty();
		AVector<ACell> loopHistory = history;

		// Runaway-loop backstop: venue default (maxToolIterations, 30),
		// overridable per agent via config.maxToolIterations.
		int maxToolIterations = resolveMaxToolIterations(config);

		for (int iteration = 0; iteration < maxToolIterations; iteration++) {
			// context_load/context_unload/skill_load mutate the immutable
			// session-tier map. Rebuild the complete ephemeral history before the
			// very next model invocation so the requesting agent sees the new
			// content immediately. The rendered messages never enter newMessages,
			// and therefore never pollute the persisted conversation transcript.
			if (toolCtx.loads != renderedLoadsKey) {
				AMap<AString, ACell> effectiveLoads =
					ContextChain.effective(toolCtx.outerLoads, toolCtx.loads);
				loopHistory = buildChatContext(
					chatContext, effectiveLoads, ctx, toolCtx.ctx).build().history();
				renderedLoadsKey = toolCtx.loads;
			}

			// Build level 3 input (fresh ephemeral context plus this loop's
			// assistant/tool exchange).
			AVector<ACell> fullHistory =
				(AVector<ACell>) loopHistory.concat(newMessages);

			// Inject dynamic task context — only outstanding (unresolved) tasks
			ACell taskMsg = buildOutstandingTaskMessage(toolCtx);
			if (taskMsg != null) {
				fullHistory = fullHistory.conj(taskMsg);
			}

			// Include task tools when tasks remain; context tools always available.
			// Loads-contributed tools (the generic "a loads entry may declare
			// tools" rule) recompute when the loads tier changes, so a
			// skill_load mid-loop activates its tools on the NEXT iteration of
			// this same transition, and an unload retracts them.
			AVector<ACell> loadTools = toolCtx.loadTools(engine);
			AVector<ACell> tools = (taskMsg != null)
				? (AVector<ACell>) TASK_TOOLS.concat(CONTEXT_TOOLS).concat(baseTools).concat(loadTools)
				: (AVector<ACell>) CONTEXT_TOOLS.concat(baseTools).concat(loadTools);

			// Dispatch to level 3 — internal, no sub-Job created
			ACell l3Result = invokeLevel3(llmOperation, config, fullHistory, tools, ctx);

			// Level 3 returns an assistant message: {role, content?, toolCalls?}
			ACell toolCallsCell = RT.getIn(l3Result, K_TOOL_CALLS);
			boolean hasToolCalls = (toolCallsCell instanceof AVector) && ((AVector<ACell>) toolCallsCell).count() > 0;

			// Textual control-tool fallback (#215): only when task tools are
			// actually on offer this iteration — outside a task cycle the same
			// text is ordinary chat.
			if (!hasToolCalls && taskMsg != null) {
				AMap<AString, ACell> rewritten = recogniseTextualControlCall(l3Result, iteration);
				if (rewritten != null) {
					log.warn("Assistant emitted a control tool as text — honouring it (#215)");
					l3Result = rewritten;
					toolCallsCell = RT.getIn(l3Result, K_TOOL_CALLS);
					hasToolCalls = true;
				}
			}

			if (!hasToolCalls) {
				// Text-only response — validate against responseFormat schema if present
				if (config != null) {
					AMap<AString, ACell> rfSchema = getResponseFormatSchema(config);
					if (rfSchema != null) {
						// Parse the content as JSON and validate against the schema
						AString content = RT.ensureString(RT.getIn(l3Result, K_CONTENT));
						if (content != null) {
							try {
								ACell parsed = convex.core.util.JSON.parse(content.toString());
								String schemaErr = JsonSchema.validate(rfSchema, parsed);
								if (schemaErr != null) {
									log.warn("LLM response schema violation: {}", schemaErr);
								}
							} catch (Exception e) {
								log.warn("LLM response not valid JSON despite responseFormat: {}", e.getMessage());
							}
						}
					}
				}
				newMessages = newMessages.conj(l3Result);
				return newMessages;
			}

			// Tool call response — record assistant message and execute tools
			newMessages = newMessages.conj(l3Result);
			AVector<ACell> toolCalls = (AVector<ACell>) toolCallsCell;

			for (long i = 0; i < toolCalls.count(); i++) {
				ACell tc = toolCalls.get(i);
				AString id = RT.ensureString(RT.getIn(tc, K_ID));
				AString name = RT.ensureString(RT.getIn(tc, K_NAME));

				// Unwrap tool arguments at the LLM wire boundary (the one
				// tolerant parse). Broken arguments fail THIS tool call with a
				// visible error the LLM can correct on its next turn — never a
				// silent Maps.empty() substitution (#89). Structured (non-string)
				// arguments pass through unchanged.
				ACell toolInput = null;
				ACell toolResult = null;
				try {
					toolInput = parseToolArguments(RT.getIn(tc, K_ARGUMENTS));
				} catch (IllegalArgumentException e) {
					String detail = describeFailure(e);
					toolResult = Strings.create("Error: " + detail);
					log.warn("Tool call {} has malformed arguments: {}", name, detail);
				}

				// Execute the tool — built-in or grid dispatch
				if (toolResult == null) {
					try {
						String toolName = (name != null) ? name.toString() : "";
						toolResult = executeToolCall(toolName, toolInput, ctx, toolCtx);
					} catch (Exception e) {
						String detail = describeFailure(e);
						toolResult = Strings.create("Error: " + detail);
						log.warn("Tool execution failed: {} — {}", name, detail);
					}
				}

				// Record failures for the transition output — the framework
				// persists them to the timeline and session so they are
				// visible after the cycle, not just to the live model (#211).
				String failure = toolFailureMessage(toolResult);
				if (failure != null) {
					toolCtx.toolFailures = toolCtx.toolFailures.conj(Maps.of(
						K_NAME, (name != null) ? name : Strings.create("unknown"),
						Fields.ERROR, Strings.create(failure)));
				}

				// Append tool result message via the shared base helper. All results
				// cross the provider boundary as textual content; structured values
				// are JSON-rendered there. Synthesises a stand-in name when the LLM omits
				// it — the message format requires a non-null name.
				String toolNameForMsg = (name != null) ? name.toString() : "unknown";
				newMessages = newMessages.conj(toolResultMessage(id, toolNameForMsg, toolResult));
			}

		}

		// Iteration limit reached — the agent gave up. Fail the transition so the
		// task resolves to FAILED instead of hanging STARTED forever behind a
		// fake-success apology (covia-ai/covia#138). A transition failure also
		// suspends the agent with the error recorded — appropriate here: an agent
		// that loops to the tool-call safety limit is misbehaving, so parking it
		// for inspection (recoverable via agent:resume) is the right reaction, not
		// silently continuing. The iteration cap already bounds CPU/IO; this makes
		// the give-up an honest, terminal outcome. (Failing only the task while
		// keeping the agent SLEEPING would need run-loop changes to distinguish a
		// task failure from an agent failure — a separate enhancement.)
		log.warn("Tool call loop reached iteration limit ({}) — failing the transition", maxToolIterations);
		throw new JobFailedException("Agent reached the tool-call iteration limit ("
			+ maxToolIterations + ") without completing the task.");
	}

	// ========== Built-in tool execution ==========

	/**
	 * Executes a tool call, dispatching to built-in handlers or grid operations.
	 * Returns ACell — either an AString (for errors/simple text) or a structured
	 * AMap/AVector. The caller converts structured results to JSON strings for
	 * the provider-compatible tool message content.
	 */
	private ACell executeToolCall(String toolName, ACell input, RequestContext ctx, ToolContext toolCtx) {
		// Built-in task tools (must mutate ToolContext directly) — always allowed
		if (TOOL_COMPLETE_TASK.equals(toolName)) return handleCompleteTask(input, toolCtx);
		if (TOOL_FAIL_TASK.equals(toolName)) return handleFailTask(input, toolCtx);

		// Built-in context tools (harness-level, like task tools)
		if (TOOL_CONTEXT_LOAD.equals(toolName)) return handleContextLoad(input, toolCtx);
		if (TOOL_CONTEXT_UNLOAD.equals(toolName)) return handleContextUnload(input, toolCtx);
		if (TOOL_SKILL_LOAD.equals(toolName)) return handleSkillLoad(input, toolCtx);

		// Cap-checked, timeout-bounded dispatch via the shared base path.
		// Resolves config tools, falls through to grid dispatch for unknown names.
		return dispatchTool(toolName, input, toolCtx.configToolMap,
			toolCtx.ctx, toolCtx.toolCallTimeoutMs);
	}

	/**
	 * Completes the in-scope task with a result by invoking the
	 * {@code agent:completeTask} venue op. The op reads {@code agentId} and
	 * {@code taskId} from the {@link RequestContext} (populated by the framework
	 * for every transition cycle), so the LLM only supplies {@code result}.
	 *
	 * <p>The venue op completes the caller's pending task Job and removes the
	 * task entry from the agent's task Index. We also record into
	 * {@link ToolContext#taskResults} so the surrounding {@code processChat}
	 * can promote the structured task output into the transition's
	 * {@code response} field for the timeline (otherwise the timeline
	 * {@code result} would just be the empty content of the assistant's
	 * tool-call message).</p>
	 */
	private ACell handleCompleteTask(ACell input, ToolContext toolCtx) {
		ACell result = RT.getIn(input, Fields.RESULT);
		if (result == null) return Strings.create(
			"Error: result is required — complete_task delivers `result` to the caller as the task output. "
			+ "Call again with the actual answer in `result`, or use fail_task if you cannot complete the task.");
		AMap<AString, ACell> opInput = Maps.of(Fields.RESULT, result);
		ACell opResult;
		try {
			opResult = engine.jobs().invokeInternal(
				"v/ops/agent/complete-task", opInput, toolCtx.ctx)
				.get(toolCtx.toolCallTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}

		// Record locally so processChat can promote the structured output
		// into the transition's response field for the timeline.
		Blob taskId = toolCtx.ctx.getTaskId();
		if (taskId != null) {
			AString taskIdStr = Strings.create(taskId.toHexString());
			toolCtx.recordTaskResult(taskIdStr,
				Maps.of(Fields.STATUS, Status.COMPLETE, Fields.OUTPUT, result));
		}

		return (opResult != null) ? opResult : Maps.empty();
	}

	/**
	 * Fails the in-scope task by invoking the {@code agent:failTask} venue op.
	 * The op reads {@code agentId} and {@code taskId} from the
	 * {@link RequestContext}; the LLM supplies an {@code error} message.
	 *
	 * <p>As with {@link #handleCompleteTask}, we record locally into
	 * {@link ToolContext#taskResults} so {@code processChat} can promote
	 * the error into the transition's {@code error} field for the timeline.
	 * The venue op completes the pending Job and removes the task entry —
	 * the framework reads completion state directly from the now-finished
	 * Job, so no separate signal is required.</p>
	 */
	private ACell handleFailTask(ACell input, ToolContext toolCtx) {
		AString error = RT.ensureString(RT.getIn(input, Fields.ERROR));
		if (error == null) return Strings.create("Error: error is required");

		AMap<AString, ACell> opInput = Maps.of(Fields.ERROR, error);
		ACell opResult;
		try {
			opResult = engine.jobs().invokeInternal(
				"v/ops/agent/fail-task", opInput, toolCtx.ctx)
				.get(toolCtx.toolCallTimeoutMs, TimeUnit.MILLISECONDS);
		} catch (Exception e) {
			return Strings.create("Error: " + unwrap(e).getMessage());
		}

		Blob taskId = toolCtx.ctx.getTaskId();
		if (taskId != null) {
			AString taskIdStr = Strings.create(taskId.toHexString());
			toolCtx.recordTaskResult(taskIdStr,
				Maps.of(Fields.STATUS, Status.FAILED, Fields.ERROR, error));
		}

		return (opResult != null) ? opResult : Maps.empty();
	}

	// ========== Built-in context tools ==========

	ACell handleContextLoad(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, K_PATH));
		if (path == null) return Strings.create("Error: path is required");
		if (!toolCtx.sessionInScope) {
			return Strings.create("Error: no session in scope — context loads are per-conversation (#142)");
		}
		try {
			new ContextLoader(engine).requireReadAccess(path, toolCtx.ctx);
		} catch (RuntimeException e) {
			return Strings.create("Error: context_load denied: " + describeFailure(e));
		}

		// Writes to the innermost tier; overwriting a tombstone un-masks locally.
		long budget = clampLoadBudget(RT.getIn(input, K_BUDGET));
		AString label = RT.ensureString(RT.getIn(input, K_LABEL));
		toolCtx.addLoad(path, buildLoadEntryMeta(budget, label));

		return Maps.of(
			K_PATH, path,
			Strings.create("loaded"), CVMBool.TRUE,
			K_BUDGET, CVMLong.create(budget),
			Strings.create("note"), Strings.create(
				"Path is loaded and will be visible on the next model invocation."));
	}

	/**
	 * {@code skill_load} — thin glue only: the adapter checks the tier is
	 * writable, delegates ALL skill semantics to {@link Skills#load}, and
	 * writes the returned entry into the loads tier exactly as
	 * {@code context_load} does. Rendering and tool activation then follow
	 * from the entry via the generic context assembly (ContextBuilder), so
	 * this runtime carries no knowledge of what a skill IS.
	 */
	ACell handleSkillLoad(ACell input, ToolContext toolCtx) {
		if (!toolCtx.sessionInScope) {
			return Strings.create("Error: no session in scope — skill loads are per-conversation (#142)");
		}
		try {
			// The effective view feeds content-identity dedup: the same skill
			// reached via a different address must not load twice.
			Skills.LoadOutcome out = Skills.load(engine, toolCtx.ctx, toolCtx.skillSources, input,
				ContextChain.effective(toolCtx.outerLoads, toolCtx.loads));
			if (out.entryMeta() != null) {
				toolCtx.addLoad(out.path(), out.entryMeta());
			}
			return out.result();
		} catch (RuntimeException e) {
			return Strings.create("Error: skill_load failed: " + describeFailure(e));
		}
	}

	ACell handleContextUnload(ACell input, ToolContext toolCtx) {
		AString path = RT.ensureString(RT.getIn(input, K_PATH));
		if (path == null) return Strings.create("Error: path is required");
		if (!toolCtx.sessionInScope) {
			return Strings.create("Error: no session in scope — context loads are per-conversation (#142)");
		}

		// Lexical unload: removes the local entry; masks an outer-tier entry
		// with a nil tombstone (this conversation only) — see ContextChain.
		AMap<AString, ACell> updated = ContextChain.unload(toolCtx.loads, toolCtx.outerLoads, path);
		if (updated == null) {
			return Strings.create("Error: path not in context: " + path);
		}
		toolCtx.loads = updated;

		return Maps.of(
			K_PATH, path,
			Strings.create("unloaded"), CVMBool.TRUE);
	}

	/**
	 * Builds a user message listing only outstanding (unresolved) tasks.
	 * Returns null if no tasks remain, signalling the loop to omit task tools.
	 */
	static AMap<AString, ACell> buildOutstandingTaskMessage(ToolContext toolCtx) {
		if (toolCtx.tasks == null || toolCtx.tasks.count() == 0) return null;
		StringBuilder sb = new StringBuilder();
		int outstanding = 0;
		for (long i = 0; i < toolCtx.tasks.count(); i++) {
			ACell task = toolCtx.tasks.get(i);
			AString jobId = RT.ensureString(RT.getIn(task, Fields.JOB_ID));
			if (jobId != null && toolCtx.taskResults != null && toolCtx.taskResults.get(jobId) != null) {
				continue; // already resolved
			}
			if (outstanding == 0) sb.append("[Tasks assigned to you]\n");
			outstanding++;
			ACell taskInput = RT.getIn(task, Fields.INPUT);
			ACell caller = RT.getIn(task, Fields.CALLER);
			sb.append("- Task ").append(jobId);
			if (caller != null) sb.append(" (from: ").append(caller).append(")");
			sb.append(": ").append(renderTaskText(taskInput)).append("\n");
		}
		if (outstanding == 0) return null;
		sb.append("Use complete_task or fail_task to resolve each task.");
		return Maps.of(K_ROLE, ROLE_USER, K_CONTENT, Strings.create(sb.toString()));
	}

	/**
	 * Renders a task input for the model: strings verbatim, anything else as
	 * JSON — never a CVM cell's EDN-style {@code toString()}, which models
	 * misread as noise (#215: qwen2.5 answered "no task details provided" to
	 * an EDN-wrapped task it handled fine as plain text).
	 */
	static String renderTaskText(ACell input) {
		if (input == null) return "";
		if (input instanceof AString s) return s.toString();
		return convex.core.util.JSON.print(input).toString();
	}

	/**
	 * Fallback recognition of control tools emitted as plain TEXT (#215):
	 * smaller models frequently write {@code complete_task {"result": ...}} /
	 * {@code fail_task {"error": ...}} as assistant text even when the tools
	 * are offered structurally. Without recognition each such turn burns a
	 * loop iteration and the task never resolves.
	 *
	 * <p>Recognised only when the text starts with the tool name and the
	 * remainder is blank or a parseable JSON object — prose that merely
	 * mentions a tool name is left alone. A bare tool name gets empty
	 * arguments so the tool's own validation surfaces what's missing.</p>
	 *
	 * @return an assistant message carrying a synthetic toolCall (original
	 *         text preserved as content), or null when the text is not a
	 *         recognisable control-tool emission
	 */
	static AMap<AString, ACell> recogniseTextualControlCall(ACell assistantMsg, int iteration) {
		AString content = RT.ensureString(RT.getIn(assistantMsg, K_CONTENT));
		if (content == null) return null;
		String text = content.toString().strip();
		String tool = null;
		if (text.startsWith(TOOL_COMPLETE_TASK)) tool = TOOL_COMPLETE_TASK;
		else if (text.startsWith(TOOL_FAIL_TASK)) tool = TOOL_FAIL_TASK;
		if (tool == null) return null;
		String rest = text.substring(tool.length()).strip();
		if (rest.startsWith(":")) rest = rest.substring(1).strip();
		ACell args;
		if (rest.isEmpty()) {
			args = Maps.empty();
		} else if (rest.startsWith("{")) {
			try {
				args = convex.core.util.JSON.parse(rest);
			} catch (Exception e) {
				return null; // not a parseable call — leave the text alone
			}
		} else {
			return null; // prose mentioning the tool name — not a call
		}
		AMap<AString, ACell> toolCall = Maps.of(
			K_ID, Strings.create("text-fallback-" + iteration),
			K_NAME, Strings.create(tool),
			K_ARGUMENTS, args);
		return Maps.of(
			K_ROLE, ROLE_ASSISTANT,
			K_CONTENT, content,
			K_TOOL_CALLS, Vectors.of(toolCall));
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	/** The names of every tool offered outside the loads mechanism this cycle
	 *  (harness pseudo-tools + config/base tools) — loads-contributed tools
	 *  dedup against these. */
	private static java.util.Set<String> fixedToolNames(AVector<ACell> baseTools) {
		java.util.Set<String> names = new java.util.HashSet<>(HARNESS_TOOL_NAMES);
		if (baseTools != null) {
			for (long i = 0; i < baseTools.count(); i++) {
				AString n = RT.ensureString(RT.getIn(baseTools.get(i), K_NAME));
				if (n != null) names.add(n.toString());
			}
		}
		return names;
	}

	/**
	 * Extracts the JSON Schema from a responseFormat config, if present.
	 * responseFormat can be: "json" (no schema), "text" (no schema), or {name, schema} (has schema).
	 */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> getResponseFormatSchema(AMap<AString, ACell> config) {
		ACell rf = config.get(K_RESPONSE_FORMAT);
		if (rf instanceof AMap) {
			ACell schema = ((AMap<AString, ACell>) rf).get(Strings.intern("schema"));
			if (schema instanceof AMap) return (AMap<AString, ACell>) schema;
		}
		return null;
	}

	// ========== Tool context ==========

	/**
	 * Mutable context passed through the tool call loop for built-in tool state.
	 */
	static class ToolContext {
		final AString agentId;
		final RequestContext ctx;
		final AVector<ACell> tasks;
		final AVector<ACell> pending;
		final Map<String, AString> configToolMap;
		final long toolCallTimeoutMs;
		AMap<AString, ACell> taskResults;
		/** The innermost writable tier's loads (the session tier, #142). */
		AMap<AString, ACell> loads;
		/** Effective loads of the OUTER tiers (agent config.loads) — read-only environment. */
		AMap<AString, ACell> outerLoads = Maps.empty();
		/** Whether a session is in scope; without one there is no writable tier. */
		boolean sessionInScope = true;
		/** Tool calls that failed this cycle, as [{name, error}] — emitted on
		 *  the transition output under {@code Fields.TOOL_FAILURES} so the
		 *  framework can persist them (timeline + session turns, #211). */
		AVector<ACell> toolFailures = Vectors.empty();
		/** Skill sources from {@code config.skills} — opaque to this runtime
		 *  ({@link Skills} owns the semantics). */
		AVector<ACell> skillSources = Vectors.empty();
		/** Tool names offered outside the loads mechanism (harness + config
		 *  tools) — loads-contributed tools dedup against these. */
		java.util.Set<String> fixedToolNames = java.util.Set.of();
		private AMap<AString, ACell> loadToolsKey;
		private AVector<ACell> loadToolsCache;

		/**
		 * Tools contributed by the effective loads — the generic "a loads
		 * entry may declare tools" rule ({@link ContextBuilder#loadsToolDefs}).
		 * Recomputed only when the writable tier changes (the maps are
		 * immutable, so a reference compare suffices): a load activates its
		 * tools on the next loop iteration, an unload retracts them. Dispatch
		 * routes accumulate into {@link #configToolMap}.
		 */
		AVector<ACell> loadTools(Engine engine) {
			if (loads == loadToolsKey && loadToolsCache != null) return loadToolsCache;
			java.util.Map<String, AString> routes = new java.util.HashMap<>();
			AVector<ACell> defs = ContextBuilder.loadsToolDefs(engine, ctx,
				ContextChain.effective(outerLoads, loads), fixedToolNames, routes);
			configToolMap.putAll(routes);
			loadToolsKey = loads;
			loadToolsCache = defs;
			return defs;
		}

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AMap<AString, ACell> loads) {
			this(agentId, ctx, tasks, pending, configToolMap, loads, DEFAULT_TOOL_CALL_TIMEOUT_MS);
		}

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AMap<AString, ACell> loads,
				long toolCallTimeoutMs) {
			this.agentId = agentId;
			this.ctx = ctx;
			this.tasks = tasks;
			this.pending = pending;
			// Mutable default: loadTools accumulates dispatch routes here.
			this.configToolMap = (configToolMap != null) ? configToolMap : new java.util.HashMap<>();
			this.loads = (loads != null) ? loads : Maps.empty();
			this.toolCallTimeoutMs = toolCallTimeoutMs;
		}

		void recordTaskResult(AString jobId, AMap<AString, ACell> result) {
			if (taskResults == null) taskResults = Maps.empty();
			taskResults = taskResults.assoc(jobId, result);
		}

		void addLoad(AString path, AMap<AString, ACell> meta) {
			loads = loads.assoc(path, meta);
		}

		AMap<AString, ACell> getLoads() {
			return loads;
		}
	}
}

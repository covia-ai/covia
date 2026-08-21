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
 * {@link ToolPalette#DEFAULT_TOOL_OPS}; capability tools arrive via skills
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
	 *   <li>{@code {role: "assistant", content: "...", toolCalls?: [{id, name, arguments: {...}}]}}</li>
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
			+ "This is TERMINAL: the caller receives `result` and nothing else. "
			+ "For a long prose answer, write the complete answer as your message text "
			+ "and call complete_task with no arguments in the same turn — the text is "
			+ "delivered as the result. Otherwise pass the answer in `result`. "
			+ "Only call this once you have the actual answer to deliver. "
			+ "The agent and task are determined from the current request context — you do not pass an id."),
		K_PARAMETERS, Maps.of(
			K_TYPE, Strings.create("object"),
			K_PROPERTIES, Maps.of(
				Fields.RESULT, Maps.of(
					K_DESCRIPTION, Strings.create("The result to return to the requester. Any JSON value — string, object, array, etc. May be omitted only when this turn's message text is the complete answer."))
			)
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
			+ "also stay in your context each turn until you remove the skill's loaded "
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
	 * Builds the L3 input for {@code agent:context} inspection: the same Spec a
	 * fresh transition would assemble — minus the provider call.
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

		RequestContext capsCtx = capsContext(recordConfig, ctx);
		ToolPalette.Palette palette = ToolPalette.resolve(engine, ctx, recordConfig, HARNESS_TOOL_NAMES);
		AVector<ACell> fixedTools = fixedTools(recordConfig, palette);
		ModelProfile profile = modelProfileFor(recordConfig, ctx);
		Loads.Snapshot loads = Loads.resolve(engine, capsCtx, effectiveLoads,
			fixedToolNames(fixedTools), profile.labels());

		// A fresh transition: no inbox and no pending results; an optional task
		// input is the outstanding task, rendered last as it would be live.
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, recordConfig,
			ContextAssembler.sessionHex(RT.getIn(session, Fields.ID)), null,
			profile.budget(), profile.labels(),
			ToolPalette.merge(fixedTools, loads.tools()), loads.elements(), effectiveLoads,
			sessionFramesOf(session), null, null, true, null,
			(taskInput != null) ? ContextAssembler.user(taskInput.toString()) : null,
			palette.unavailable(), null, null);
		return ContextAssembler.assemble(spec).toL3Input(recordConfig);
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

		AVector<ACell> sessionFrames = AgentAdapter.sessionFrames(input);
		RequestContext capsCtx = capsContext(recordConfig, ctx).withAgentId(agentId);
		ToolPalette.Palette palette = ToolPalette.resolve(engine, ctx, recordConfig, HARNESS_TOOL_NAMES);
		AMap<AString, ACell> config = recordConfig;
		AString llmOperation = getLLMOperation(config);
		AVector<ACell> fixedTools = fixedTools(config, palette);
		ModelProfile profile = modelProfileFor(config, ctx);

		// Tool context for harness-tool execution. Its loads slot is the SESSION
		// tier (the innermost writable tier for this runtime); the agent tier
		// rides along read-only for unload masking decisions.
		long toolCallTimeoutMs = resolveToolCallTimeoutMs(config);
		ToolContext toolCtx = new ToolContext(agentId, capsCtx, tasks, pending, palette.routes(),
			sessionTier, toolCallTimeoutMs);
		toolCtx.outerLoads = configLoads;
		toolCtx.sessionInScope = sessionInScope;
		toolCtx.skillSources = Skills.sourcesOf(config);
		toolCtx.fixedToolNames = fixedToolNames(fixedTools);

		// Everything the assembler needs for this cycle; the loop supplies what
		// changes per inference — loads, this cycle's turns, the outstanding task.
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(),
			fixedTools, null, null, sessionFrames, pending, messages, hasInput, null, null,
			palette.unavailable(), null, null);

		// ctx (uncapped) makes the provider call; capsCtx flows through toolCtx
		// for tool dispatch.
		AVector<ACell> newMessages = invokeWithToolLoop(llmOperation, spec, fixedTools, ctx, toolCtx);

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

	/** The tools fixed for a cycle — harness tools, then the configured palette. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> fixedTools(AMap<AString, ACell> config, ToolPalette.Palette palette) {
		AVector<ACell> harness = CONTEXT_TOOLS;
		// skill_load is offered only when the agent declares skill sources; the
		// adapter holds no skills semantics beyond offering it.
		if (!Skills.sourcesOf(config).isEmpty()) {
			harness = (AVector<ACell>) Vectors.of((ACell) TOOL_DEF_SKILL_LOAD).concat(harness);
		}
		return (AVector<ACell>) harness.concat(palette.tools());
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
			AString llmOperation, ContextAssembler.Spec spec,
			AVector<ACell> fixedTools, RequestContext ctx, ToolContext toolCtx) {
		AMap<AString, ACell> config = spec.config();
		AVector<ACell> messages = Vectors.empty();
		int maxToolIterations = resolveMaxToolIterations(config);
		ToolCycleEngine.Registry<ToolContext> registry = toolRegistry();
		@SuppressWarnings("unchecked")
		final AVector<ACell>[] sinkMessages = new AVector[] { messages };
		ToolCycleEngine.BatchSink sink = new ToolCycleEngine.BatchSink() {
			@Override
			public void append(AMap<AString, ACell> message) {
				sinkMessages[0] = sinkMessages[0].conj(message);
			}

			@Override
			public void recordFailure(AString name, String failure) {
				toolCtx.toolFailures = toolCtx.toolFailures.conj(Maps.of(
					K_NAME, name, Fields.ERROR, Strings.create(failure)));
			}
		};

		for (int iteration = 0; iteration < maxToolIterations; iteration++) {
			// The whole prompt is rebuilt before every call: loads re-read, this
			// cycle's turns in band, the tail re-rendered after them.
			AMap<AString, ACell> effectiveLoads =
				ContextChain.effective(toolCtx.outerLoads, toolCtx.loads);
			Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, spec.labels());
			ACell taskMessage = buildOutstandingTaskMessage(toolCtx);
			AVector<ACell> tools = ToolPalette.merge(
				(taskMessage != null) ? (AVector<ACell>) TASK_TOOLS.concat(fixedTools) : fixedTools,
				loads.tools());
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(
				spec.withLoads(loads, tools, effectiveLoads).withToolLoop(messages).withTask(taskMessage));

			ACell assistant = invokeLevel3(llmOperation, config, prompt.messages(), prompt.tools(), ctx);
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls && taskMessage != null) {
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration,
					java.util.Set.of(TOOL_COMPLETE_TASK, TOOL_FAIL_TASK));
				if (rewritten != null) {
					log.warn("Assistant emitted a harness control tool as text — honouring it (#215)");
					assistant = rewritten;
					calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
					hasCalls = true;
				}
			}

			if (!hasCalls) {
				if (config != null) {
					AMap<AString, ACell> schema = getResponseFormatSchema(config);
					AString content = RT.ensureString(RT.getIn(assistant, K_CONTENT));
					if (schema != null && content != null) {
						try {
							ACell parsed = convex.core.util.JSON.parse(content.toString());
							String error = JsonSchema.validate(schema, parsed);
							if (error != null) log.warn("LLM response schema violation: {}", error);
						} catch (Exception e) {
							log.warn("LLM response not valid JSON despite responseFormat: {}", e.getMessage());
						}
					}
				}
				return messages.conj(assistant);
			}

			messages = messages.conj(assistant);
			toolCtx.turnText = RT.ensureString(RT.getIn(assistant, K_CONTENT));
			sinkMessages[0] = messages;
			ToolCycleEngine.executeBatch(calls, iteration, registry, toolCtx, sink, log);
			messages = sinkMessages[0];
		}

		log.warn("Tool call loop reached iteration limit ({}) — failing the transition", maxToolIterations);
		throw new JobFailedException("Agent reached the tool-call iteration limit ("
			+ maxToolIterations + ") without completing the task.");
	}

	/**
	 * Per-cycle harness registry. Completion becomes terminal only after the
	 * lifecycle venue operation succeeds; a rejected completion remains
	 * retryable and does not fence later calls in the provider batch.
	 */
	private ToolCycleEngine.Registry<ToolContext> toolRegistry() {
		return new ToolCycleEngine.Registry<ToolContext>()
			.register(TOOL_COMPLETE_TASK, (call, toolCtx) -> {
				long before = toolCtx.taskResultCount();
				ACell result = handleCompleteTask(call.input(), toolCtx);
				return (toolCtx.taskResultCount() > before)
					? ToolCycleEngine.ToolOutcome.terminal(result, TOOL_COMPLETE_TASK,
						RT.getIn(call.input(), Fields.RESULT))
					: ToolCycleEngine.ToolOutcome.result(result);
			})
			.register(TOOL_FAIL_TASK, (call, toolCtx) -> {
				long before = toolCtx.taskResultCount();
				ACell result = handleFailTask(call.input(), toolCtx);
				return (toolCtx.taskResultCount() > before)
					? ToolCycleEngine.ToolOutcome.terminal(result, TOOL_FAIL_TASK,
						RT.getIn(call.input(), Fields.ERROR))
					: ToolCycleEngine.ToolOutcome.result(result);
			})
			.register(TOOL_CONTEXT_LOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleContextLoad(call.input(), toolCtx)))
			.register(TOOL_CONTEXT_UNLOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleContextUnload(call.input(), toolCtx)))
			.register(TOOL_SKILL_LOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleSkillLoad(call.input(), toolCtx)))
			.fallback((call, toolCtx) -> ToolCycleEngine.ToolOutcome.result(
				dispatchTool(call.name(), call.input(), toolCtx.dispatchRoutes(),
					toolCtx.ctx, toolCtx.toolCallTimeoutMs)));
	}

	// ========== Built-in tool execution ==========

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
		if (isBlankResult(result)) {
			// The dual of #215 (a control call emitted as text): the answer
			// emitted as text alongside an empty control call. The model has
			// already delivered the payload as its message content — honour it
			// rather than demanding a re-marshalled copy of prose it just
			// wrote, which long answers reliably fail to produce.
			AString text = toolCtx.turnText;
			if (text != null && !text.toString().isBlank()) {
				result = text;
			} else {
				return Strings.create(
					"Error: result is required — complete_task delivers `result` to the caller as the task "
					+ "output, and this turn carried no message text to use instead. Call again with the "
					+ "actual answer in `result` (or write the answer as your message text and call "
					+ "complete_task in the same turn), or use fail_task if you cannot complete the task.");
			}
		}

		// Requester-declared response schema with strict enforcement (#376):
		// the contract is checked at the completion boundary; what the agent
		// does about a rejection is its own business. Non-strict schemas are
		// guidance only — never validated here.
		AMap<AString, ACell> task = inScopeTask(toolCtx);
		if (task != null && CVMBool.TRUE.equals(task.get(Fields.STRICT))) {
			AMap<AString, ACell> schema = RT.ensureMap(task.get(Fields.RESPONSE_SCHEMA));
			if (schema != null) {
				// A string result (typed or the text fallback) often carries the
				// JSON as text — parse it before judging, so a text-emitted
				// object satisfies an object schema.
				ACell candidate = result;
				AString s = RT.ensureString(result);
				if (s != null) {
					try {
						ACell parsed = convex.core.util.JSON.parse(s.toString());
						if (JsonSchema.validate(schema, parsed) == null) candidate = parsed;
					} catch (Exception e) {
						// Not JSON — the raw string is judged against the schema.
					}
				}
				String schemaErr = JsonSchema.validate(schema, candidate);
				if (schemaErr != null) {
					return Strings.create(
						"Error: result does not conform to this task's response schema — " + schemaErr
						+ ". The requester requires: " + convex.core.util.JSON.print(schema)
						+ ". Correct the result and call complete_task again, or use fail_task if you cannot.");
				}
				result = candidate;
			}
		}
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

	/** A completion payload the model plainly didn't fill: absent, or a blank
	 *  string. Structured values (maps, vectors, numbers) are never blank. */
	private static boolean isBlankResult(ACell result) {
		if (result == null) return true;
		AString s = RT.ensureString(result);
		return s != null && s.toString().isBlank();
	}

	/** The wire-shape task entry matching the request context's in-scope task
	 *  id, or null. Task entries carry {jobId, input, caller?, responseSchema?,
	 *  strict?} (AgentAdapter.formatTask). */
	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> inScopeTask(ToolContext toolCtx) {
		Blob taskId = toolCtx.ctx.getTaskId();
		if (taskId == null || toolCtx.tasks == null) return null;
		String hex = taskId.toHexString();
		for (long i = 0; i < toolCtx.tasks.count(); i++) {
			ACell t = toolCtx.tasks.get(i);
			if (!(t instanceof AMap)) continue;
			AString jobId = RT.ensureString(RT.getIn(t, Fields.JOB_ID));
			if (jobId != null && hex.equals(jobId.toString())) return (AMap<AString, ACell>) t;
		}
		return null;
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
		if (error == null) return Strings.create(
			"Error: error is required — fail_task reports why the task cannot be completed. "
			+ "Call again with the reason in `error`, e.g. {\"error\": \"why it failed\"}.");

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
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — context loads are per-conversation (#142)");
		ACell result = HarnessTools.contextLoad(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	/**
	 * {@code skill_load} — thin glue only: the adapter checks the tier is
	 * writable, delegates ALL skill semantics to {@link Skills#load}, and
	 * writes the returned entry into the loads tier exactly as
	 * {@code context_load} does. Rendering and tool activation then follow
	 * from the entry via the generic context assembly (ContextAssembler), so
	 * this runtime carries no knowledge of what a skill IS.
	 */
	ACell handleSkillLoad(ACell input, ToolContext toolCtx) {
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — skill loads are per-conversation (#142)");
		ACell result = HarnessTools.skillLoad(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	ACell handleContextUnload(ACell input, ToolContext toolCtx) {
		HarnessTools.LoadScope scope = loadScope(toolCtx,
			"Error: no session in scope — context loads are per-conversation (#142)");
		ACell result = HarnessTools.contextUnload(input, scope);
		toolCtx.loads = scope.loads;
		return result;
	}

	private HarnessTools.LoadScope loadScope(ToolContext toolCtx, String unavailableMessage) {
		return new HarnessTools.LoadScope(engine, toolCtx.ctx, toolCtx.loads,
			toolCtx.outerLoads, toolCtx.sessionInScope, unavailableMessage,
			toolCtx.skillSources);
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
			// The requester's declared result shape (#376) — advisory unless
			// the requester opted into enforcement.
			ACell schema = RT.getIn(task, Fields.RESPONSE_SCHEMA);
			if (schema != null) {
				boolean strict = CVMBool.TRUE.equals(RT.getIn(task, Fields.STRICT));
				sb.append("  Response schema").append(strict
						? " (enforced — complete_task results must conform)"
						: " (guidance)")
					.append(": ").append(convex.core.util.JSON.print(schema)).append("\n");
			}
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
		return ToolCycleEngine.recogniseTextualControlCall(assistantMsg, iteration,
			java.util.Set.of(TOOL_COMPLETE_TASK, TOOL_FAIL_TASK));
	}

	static AString getConfigValue(AMap<AString, ACell> config, AString key, AString defaultValue) {
		if (config == null) return defaultValue;
		AString val = RT.ensureString(config.get(key));
		return (val != null) ? val : defaultValue;
	}

	/** The names of every tool offered outside the loads mechanism this cycle
	 *  (harness pseudo-tools + config/base tools) — loads-contributed tools
	 *  dedup against these. */
	private static java.util.Set<String> fixedToolNames(AVector<ACell> tools) {
		java.util.Set<String> names = new java.util.HashSet<>(HARNESS_TOOL_NAMES);
		names.addAll(ToolPalette.names(tools));
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
		/** Text content of the assistant message whose tool batch is currently
		 *  executing — the fallback payload for an empty {@code complete_task}
		 *  (the dual of #215: control emitted as a tool, answer emitted as
		 *  text). Null when the turn carried no text. */
		AString turnText;
		/** Skill sources from {@code config.skills} + {@code config.skillsets} —
		 *  opaque to this runtime ({@link Skills} owns the semantics). */
		Skills.SkillSources skillSources = Skills.SkillSources.EMPTY;
		/** Tool names offered outside the loads mechanism (harness + config
		 *  tools) — loads-contributed tools dedup against these. */
		java.util.Set<String> fixedToolNames = java.util.Set.of();
		private Map<String, AString> currentLoadRoutes = Map.of();

		/**
		 * Tools contributed by the effective loads — the generic "a loads
		 * entry may declare tools" rule ({@link ToolPalette#loadsToolDefs}).
		 * Resolved fresh for every inference together with loaded values. The
		 * route set is replaced atomically, so unloading a source retracts both
		 * its advertised definition and its name-to-operation dispatch route.
		 */
		AVector<ACell> loadTools(Engine engine) {
			return refreshLoadSnapshot(engine, Labels.BRACKET).tools();
		}

		Loads.Snapshot refreshLoadSnapshot(Engine engine, AString labels) {
			Loads.Snapshot snapshot = Loads.resolve(engine, ctx,
				ContextChain.effective(outerLoads, loads), fixedToolNames, labels);
			currentLoadRoutes = snapshot.routes();
			return snapshot;
		}

		Map<String, AString> dispatchRoutes() {
			if (currentLoadRoutes.isEmpty()) return configToolMap;
			Map<String, AString> effective = new java.util.HashMap<>(configToolMap);
			effective.putAll(currentLoadRoutes);
			return effective;
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
			// Mutable base routes may grow through runtime mechanisms; live-load
			// routes are held separately and replaced every inference.
			this.configToolMap = (configToolMap != null) ? configToolMap : new java.util.HashMap<>();
			this.loads = (loads != null) ? loads : Maps.empty();
			this.toolCallTimeoutMs = toolCallTimeoutMs;
		}

		void recordTaskResult(AString jobId, AMap<AString, ACell> result) {
			if (taskResults == null) taskResults = Maps.empty();
			taskResults = taskResults.assoc(jobId, result);
		}

		long taskResultCount() {
			return (taskResults != null) ? taskResults.count() : 0;
		}

		void addLoad(AString path, AMap<AString, ACell> meta) {
			loads = loads.assoc(path, meta);
		}

		AMap<AString, ACell> getLoads() {
			return loads;
		}
	}
}

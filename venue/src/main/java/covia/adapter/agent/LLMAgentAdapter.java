package covia.adapter.agent;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AgentAdapter;
import covia.adapter.agent.ContextInspectable.Inspection;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
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

	// ========== Default tool definitions ==========
	// MCP-style: {name, description, parameters: {type: "object", properties: {...}, required: [...]}}

	/** Harness pseudo-tools this runtime provides — intercepted by the adapter,
	 *  never dispatched as operations (see {@link AbstractLLMAdapter#dispatchTool}):
	 *  the shared registry plus the task tools. */
	static final java.util.Set<String> HARNESS_TOOL_NAMES;
	static {
		java.util.Set<String> names = new java.util.HashSet<>(HarnessTools.SHARED.keySet());
		names.addAll(TaskTools.NAMES);
		HARNESS_TOOL_NAMES = java.util.Set.copyOf(names);
	}

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

	/** What inspection and step share with a live cycle: the Spec, the tool
	 *  context the harness tools run against, and the cycle's fixed tools. */
	private record Preview(ContextAssembler.Spec spec, ToolContext toolCtx, AVector<ACell> fixedTools) {}

	/**
	 * The transition a call with these inputs would start: the session's
	 * conversation, the inbox, pending results, and a task rendered exactly as
	 * the tool loop renders it — task tools included.
	 */
	@SuppressWarnings("unchecked")
	private Preview preview(Inspection in, RequestContext ctx) {
		AMap<AString, ACell> config = effectiveModelConfig(in.config(), ctx);
		// Same scope-chain view as processChat (agent tier + session tier), so
		// the inspected skills index carries the right (loaded) markers.
		AMap<AString, ACell> configLoads = ContextChain.declaredLoads(
			RT.getIn(config, Fields.LOADS), "config.loads");
		ACell sessLoads = RT.getIn(in.session(), Fields.LOADS);
		AMap<AString, ACell> sessionTier = (sessLoads instanceof AMap)
			? (AMap<AString, ACell>) sessLoads : null;

		RequestContext capsCtx = capsContext(config, ctx);
		ToolPalette.Palette palette = ToolPalette.resolve(engine, ctx, config, HARNESS_TOOL_NAMES);
		ModelProfile profile = modelProfileFor(config, ctx);
		AVector<ACell> fixedTools = fixedTools(config, palette);

		// The task renders through the tool loop's own renderer — a preview
		// job id stands in for the one a real task would carry.
		AVector<ACell> tasks = (in.task() != null)
			? Vectors.of((ACell) Maps.of(Fields.JOB_ID, TaskTools.PREVIEW_JOB_ID, Fields.INPUT, in.task()))
			: null;
		ToolContext toolCtx = toolContext(config, capsCtx, tasks, in.pending(), palette,
			configLoads, sessionTier, in.session() != null, fixedTools, true);
		ACell task = toolCtx.tasks.message();
		AVector<ACell> tools = (AVector<ACell>) toolCtx.tasks.tools().concat(fixedTools);
		Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, profile.labels());
		boolean hasInput = (in.messages() != null && in.messages().count() > 0)
			|| (in.pending() != null && in.pending().count() > 0)
			|| task != null;

		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(in.session(), Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
			ToolPalette.merge(tools, loads.tools()), loads.elements(),
			ContextChain.effective(configLoads, sessionTier),
			sessionFramesOf(in.session()), in.pending(), in.messages(), hasInput, null, task,
			palette.unavailable(), null, null);
		return new Preview(spec, toolCtx, fixedTools);
	}

	@Override
	protected ContextAssembler.Spec inspectionSpec(Inspection in, RequestContext ctx) {
		return preview(in, ctx).spec();
	}

	/**
	 * One iteration of the tool loop on the supplied reply: text-as-control
	 * recognised as live, the batch dispatched through the live registry —
	 * a task resolution judged and recorded but never reaching a job — and the next
	 * prompt rebuilt as the loop rebuilds it: loads re-read, a resolved task
	 * gone from the tail, this iteration's turns in band.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public AMap<AString, ACell> stepContext(Inspection in, AMap<AString, ACell> assistant, RequestContext ctx) {
		Preview p = preview(in, ctx);
		ToolContext toolCtx = p.toolCtx();
		AMap<AString, ACell> reply = assistant;
		AVector<ACell> calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
		if (calls == null && p.spec().task() != null) {
			AMap<AString, ACell> rewritten = recogniseTextualControlCall(reply, 0);
			if (rewritten != null) {
				reply = rewritten;
				calls = RT.ensureVector(reply.get(K_TOOL_CALLS));
			}
		}
		if (calls == null) return Step.done(reply, reply.get(K_CONTENT)).report();

		toolCtx.turnText = RT.ensureString(reply.get(K_CONTENT));
		StepSink sink = new StepSink();
		ToolCycleEngine.BatchResult batch = ToolCycleEngine.executeBatch(
			calls, 0, toolRegistry(), toolCtx, sink, log);
		AVector<ACell> turns = Vectors.of((ACell) reply).concat(sink.turns());

		Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, p.spec().labels());
		ACell task = toolCtx.tasks.message();
		AVector<ACell> tools = ToolPalette.merge(
			(AVector<ACell>) toolCtx.tasks.tools().concat(p.fixedTools()).concat(toolCtx.addedTools), loads.tools());
		ContextAssembler.Spec next = p.spec()
			.withLoads(loads, tools, ContextChain.effective(toolCtx.outerLoads, toolCtx.loads))
			.withToolLoop(turns)
			.withTask(task);
		return new Step(reply, turns, sink, batch.terminalStatus(), batch.terminalValue(), null, next).report();
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
	ACell processChat(RequestContext ctx, ACell input) {
		// The cycle record (#392) collects every inference and tool call made
		// below — thread-confined, nothing threaded through the loop — and
		// rides out on the output, or on the failure that ends the cycle.
		CycleRecord.begin();
		try {
			return chat(ctx, input);
		} catch (RuntimeException e) {
			throw CycleRecord.Failure.of(e);
		}
	}

	@SuppressWarnings("unchecked")
	private ACell chat(RequestContext ctx, ACell input) {
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
		AMap<AString, ACell> config = effectiveModelConfig(recordConfig, ctx);
		AString llmOperation = getLLMOperation(config);
		AVector<ACell> fixedTools = fixedTools(config, palette);
		ModelProfile profile = modelProfileFor(config, ctx);

		ToolContext toolCtx = toolContext(config, capsCtx, tasks, pending, palette,
			configLoads, sessionTier, sessionInScope, fixedTools, false);

		// Everything the assembler needs for this cycle; the loop supplies what
		// changes per inference — loads, this cycle's turns, the outstanding task.
		ContextAssembler.Spec spec = new ContextAssembler.Spec(
			engine, ctx, capsCtx, config,
			ContextAssembler.sessionHex(RT.getIn(input, Fields.SESSION, Fields.ID)), null,
			profile.budget(), profile.labels(), profile.toolCalling(),
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

		output = toolCtx.tasks.promote(output);

		// The cycle record and its token totals (#217: measured only; absent
		// means the provider reported nothing, never zero).
		CycleRecord.Result cycle = CycleRecord.end();
		output = output.assoc(Fields.CYCLE, cycle.cycle());
		if (cycle.tokens() != null) output = output.assoc(Fields.TOKENS, cycle.tokens());
		return output;
	}

	/**
	 * The context harness tools run against for one cycle. Its loads slot is
	 * the SESSION tier (the innermost writable tier for this runtime); the
	 * agent tier rides along read-only for unload masking decisions.
	 */
	private ToolContext toolContext(AMap<AString, ACell> config, RequestContext capsCtx,
			AVector<ACell> tasks, AVector<ACell> pending, ToolPalette.Palette palette,
			AMap<AString, ACell> configLoads, AMap<AString, ACell> sessionTier,
			boolean sessionInScope, AVector<ACell> fixedTools, boolean preview) {
		long timeoutMs = resolveToolCallTimeoutMs(config);
		ToolContext toolCtx = new ToolContext(capsCtx.getAgentId(), capsCtx,
			new TaskTools.Tasks(engine, capsCtx, tasks, timeoutMs, preview), pending,
			palette.routes(), sessionTier, timeoutMs);
		toolCtx.outerLoads = configLoads;
		toolCtx.sessionInScope = sessionInScope;
		toolCtx.skillSources = Skills.sourcesOf(config);
		toolCtx.fixedToolNames = fixedToolNames(fixedTools);
		return toolCtx;
	}

	/** The tools fixed for a cycle — the harness tools offered (HarnessTools:
	 *  opt-in by name, skill_load and context_unload implied by declared
	 *  skills), then the configured palette. */
	@SuppressWarnings("unchecked")
	private static AVector<ACell> fixedTools(AMap<AString, ACell> config, ToolPalette.Palette palette) {
		return (AVector<ACell>) HarnessTools.offered(config, HarnessTools.SHARED).concat(palette.tools());
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
		};

		for (int iteration = 0; iteration < maxToolIterations; iteration++) {
			// The whole prompt is rebuilt before every call: loads re-read, this
			// cycle's turns in band, the tail re-rendered after them.
			AMap<AString, ACell> effectiveLoads =
				ContextChain.effective(toolCtx.outerLoads, toolCtx.loads);
			Loads.Snapshot loads = toolCtx.refreshLoadSnapshot(engine, spec.labels());
			ACell taskMessage = toolCtx.tasks.message();
			AVector<ACell> tools = ToolPalette.merge(
				(AVector<ACell>) toolCtx.tasks.tools().concat(fixedTools).concat(toolCtx.addedTools), loads.tools());
			ContextAssembler.Prompt prompt = ContextAssembler.assemble(
				spec.withLoads(loads, tools, effectiveLoads).withToolLoop(messages).withTask(taskMessage));

			ACell assistant = invokeLevel3(llmOperation, config, prompt, ctx);
			AVector<ACell> calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
			boolean hasCalls = calls != null && calls.count() > 0;
			if (!hasCalls && taskMessage != null) {
				AMap<AString, ACell> rewritten = ToolCycleEngine.recogniseTextualControlCall(
					assistant, iteration,
					TaskTools.NAMES);
				if (rewritten != null) {
					log.warn("Assistant emitted a harness control tool as text — honouring it (#215)");
					assistant = rewritten;
					calls = RT.ensureVector(RT.getIn(assistant, K_TOOL_CALLS));
					hasCalls = true;
				}
			}

			if (!hasCalls) {
				if (config != null) {
					// responseFormat is a provider hint here, not a contract: the
					// same judgement as a typed completion, logged rather than enforced.
					AMap<AString, ACell> schema = getResponseFormatSchema(config);
					AString content = RT.ensureString(RT.getIn(assistant, K_CONTENT));
					if (schema != null && content != null) {
						Completion completion = Completion.of(content, null, schema, null);
						if (!completion.accepted()) log.warn("LLM response schema violation: {}", completion.rejection());
					}
				}
				return messages.conj(stampTs(assistant));
			}

			messages = messages.conj(stampTs(assistant));
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
			.register(TaskTools.COMPLETE, (call, toolCtx) -> toolCtx.tasks.complete(call, toolCtx.turnText))
			.register(TaskTools.FAIL, (call, toolCtx) -> toolCtx.tasks.fail(call, toolCtx.turnText))
			.register(HarnessTools.CONTEXT_LOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleContextLoad(call.input(), toolCtx)))
			.register(HarnessTools.CONTEXT_UNLOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleContextUnload(call.input(), toolCtx)))
			.register(HarnessTools.SKILL_LOAD, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(handleSkillLoad(call.input(), toolCtx)))
			.register(HarnessTools.MORE_TOOLS, (call, toolCtx) ->
				ToolCycleEngine.ToolOutcome.result(toolCtx.moreTools(engine, call.input())))
			.fallback((call, toolCtx) -> ToolCycleEngine.ToolOutcome.result(
				dispatchTool(call.name(), call.input(), toolCtx.dispatchRoutes(),
					toolCtx.ctx, toolCtx.toolCallTimeoutMs)));
	}

	// ========== Built-in tool execution ==========

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

	/** @see TaskTools#renderTaskText */
	static String renderTaskText(ACell input) {
		return TaskTools.renderTaskText(input);
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
			TaskTools.NAMES);
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
		final TaskTools.Tasks tasks;
		final AVector<ACell> pending;
		final Map<String, AString> configToolMap;
		final long toolCallTimeoutMs;
		/** The innermost writable tier's loads (the session tier, #142). */
		AMap<AString, ACell> loads;
		/** Effective loads of the OUTER tiers (agent config.loads) — read-only environment. */
		AMap<AString, ACell> outerLoads = Maps.empty();
		/** Whether a session is in scope; without one there is no writable tier. */
		boolean sessionInScope = true;
		/** Text content of the assistant message whose tool batch is currently
		 *  executing — the fallback payload for an empty {@code complete_task}
		 *  (the dual of #215: control emitted as a tool, answer emitted as
		 *  text). Null when the turn carried no text. */
		AString turnText;
		/** Skill sources from {@code config.skills} + {@code config.skillsets} —
		 *  opaque to this runtime ({@link Skills} owns the semantics). */
		Skills.SkillSources skillSources = Skills.SkillSources.EMPTY;
		/** Tool names offered outside the loads mechanism (harness + config
		 *  tools, and whatever more_tools adds) — loads-contributed tools dedup
		 *  against these. */
		java.util.Set<String> fixedToolNames = new java.util.HashSet<>();
		/** Operations added by more_tools this run: offered from the next inference. */
		AVector<ACell> addedTools = Vectors.empty();
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

		/** {@code more_tools}: the additions join this run's palette and routes. */
		ACell moreTools(Engine engine, ACell input) {
			HarnessTools.Added added = HarnessTools.moreTools(input, engine, ctx, fixedToolNames, configToolMap);
			addedTools = (AVector<ACell>) addedTools.concat(added.tools());
			return added.result();
		}

		Map<String, AString> dispatchRoutes() {
			if (currentLoadRoutes.isEmpty()) return configToolMap;
			Map<String, AString> effective = new java.util.HashMap<>(configToolMap);
			effective.putAll(currentLoadRoutes);
			return effective;
		}

		ToolContext(AString agentId, RequestContext ctx, AVector<ACell> tasks, AVector<ACell> pending,
				Map<String, AString> configToolMap, AMap<AString, ACell> loads) {
			// No live task boundary: the tasks resolve in preview, never reaching a job.
			this(agentId, ctx, new TaskTools.Tasks(null, ctx, tasks, DEFAULT_TOOL_CALL_TIMEOUT_MS, true),
				pending, configToolMap, loads, DEFAULT_TOOL_CALL_TIMEOUT_MS);
		}

		ToolContext(AString agentId, RequestContext ctx, TaskTools.Tasks tasks, AVector<ACell> pending,
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

		void addLoad(AString path, AMap<AString, ACell> meta) {
			loads = loads.assoc(path, meta);
		}

		AMap<AString, ACell> getLoads() {
			return loads;
		}
	}
}

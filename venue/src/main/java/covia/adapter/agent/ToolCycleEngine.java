package covia.adapter.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;

/**
 * Shared mechanics for one LLM tool-call cycle.
 *
 * <p>The flat and goal-tree harnesses deliberately keep different conversation
 * stores and completion policies. The provider-facing batch protocol is not a
 * policy, however: arguments are decoded once, every call id is paired with a
 * result, failures are made observable, and a successful terminal call fences
 * later side effects. Keeping those rules here prevents fixes from landing in
 * only one harness.</p>
 *
 * <p>This class intentionally does not own the outer loop. {@code AgentAdapter}
 * already runs each agent on one virtual thread, while the flat harness returns
 * turns for the framework to merge and the goal-tree harness persists each frame
 * turn immediately. Both loops stay as ordinary control flow and call these
 * helpers; hiding that persistence difference behind callbacks makes the code
 * harder to follow without removing any real policy.</p>
 */
final class ToolCycleEngine {

	private ToolCycleEngine() {}

	/** A decoded provider tool call. */
	record ToolCall(AString id, String name, ACell input, int iteration) {}

	/**
	 * Result returned by a registered harness handler.
	 *
	 * @param result provider-visible result (ignored when appendResult is false)
	 * @param terminalStatus non-null only after a terminal request succeeded
	 * @param terminalValue harness result carried by that terminal request
	 * @param appendResult false when a handler persisted its result atomically
	 * @param aborted true when the surrounding cycle must stop immediately
	 */
	record ToolOutcome(ACell result, String terminalStatus, ACell terminalValue,
			boolean appendResult, boolean aborted) {

		static ToolOutcome result(ACell result) {
			return new ToolOutcome(result, null, null, true, false);
		}

		static ToolOutcome recorded() {
			return recorded(null);
		}

		/** A handler that persisted its result itself; {@code result} is what
		 *  it recorded, kept for the cycle record. */
		static ToolOutcome recorded(ACell result) {
			return new ToolOutcome(result, null, null, false, false);
		}

		static ToolOutcome terminal(ACell result, String status, ACell value) {
			return new ToolOutcome(result, status, value, true, false);
		}

		static ToolOutcome abort() {
			return new ToolOutcome(null, null, null, false, true);
		}
	}

	/** Outcome of a complete provider batch. */
	record BatchResult(String terminalStatus, ACell terminalValue, boolean aborted) {
		boolean isTerminal() { return terminalStatus != null; }
		boolean isAborted() { return aborted; }
	}

	@FunctionalInterface
	interface ToolHandler<C> {
		ToolOutcome handle(ToolCall call, C context) throws Exception;
	}

	/** Name-to-handler registry with one fallback for ordinary operation tools. */
	static final class Registry<C> {
		private final Map<String, ToolHandler<C>> handlers = new HashMap<>();
		private ToolHandler<C> fallback;

		Registry<C> register(String name, ToolHandler<C> handler) {
			handlers.put(name, handler);
			return this;
		}

		Registry<C> fallback(ToolHandler<C> handler) {
			fallback = handler;
			return this;
		}

		ToolOutcome dispatch(ToolCall call, C context) throws Exception {
			ToolHandler<C> handler = handlers.get(call.name());
			if (handler == null) handler = fallback;
			if (handler == null) {
				return ToolOutcome.result(Strings.create("Error: unknown tool: " + call.name()));
			}
			return handler.handle(call, context);
		}
	}

	/** Adapter-specific turn persistence and cycle diagnostics. */
	interface BatchSink {
		void append(AMap<AString, ACell> message);
		/** Every call that reached a handler, with its outcome and wall-clock
		 *  milliseconds. Calls fenced after a terminal request never get here. */
		default void recordCall(ToolCall call, ToolOutcome outcome, long millis) {}
	}

	/**
	 * Executes one provider tool-call batch with the shared wire invariants.
	 */
	@SuppressWarnings("unchecked")
	static <C> BatchResult executeBatch(AVector<ACell> toolCalls, int iteration,
			Registry<C> registry, C context, BatchSink sink, Logger log) {
		String terminalStatus = null;
		ACell terminalValue = null;

		for (long i = 0; i < toolCalls.count(); i++) {
			ACell rawCall = toolCalls.get(i);
			AString id = RT.ensureString(RT.getIn(rawCall, AbstractLLMAdapter.K_ID));
			AString nameCell = RT.ensureString(RT.getIn(rawCall, AbstractLLMAdapter.K_NAME));
			String name = (nameCell != null) ? nameCell.toString() : "unknown";

			// Provider protocols require a result for every call in a parallel
			// batch, even though later side effects must be fenced after terminal.
			if (terminalStatus != null) {
				sink.append(stamped(AbstractLLMAdapter.toolResultMessage(id, name, Strings.create(
					"Error: not executed — an earlier call in this tool batch was terminal ("
					+ terminalStatus + ")."))));
				continue;
			}

			ACell toolInput = null;
			ToolOutcome outcome = null;
			try {
				toolInput = AbstractLLMAdapter.parseToolArguments(
					RT.getIn(rawCall, AbstractLLMAdapter.K_ARGUMENTS));
			} catch (IllegalArgumentException e) {
				String detail = describe(e);
				outcome = ToolOutcome.result(Strings.create("Error: " + detail));
				log.warn("Tool call {} has malformed arguments: {}", name, detail);
			}

			ToolCall call = new ToolCall(id, name, toolInput, iteration);
			long started = System.nanoTime();
			if (outcome == null) {
				try {
					outcome = registry.dispatch(call, context);
					if (outcome == null) {
						outcome = ToolOutcome.result(Strings.create(
							"Error: tool handler returned no outcome: " + name));
					}
				} catch (Exception e) {
					String detail = describe(e);
					outcome = ToolOutcome.result(Strings.create("Error: " + detail));
					log.warn("Tool execution failed: {} — {}", name, detail);
				}
			}
			long millis = (System.nanoTime() - started) / 1_000_000;
			sink.recordCall(call, outcome, millis);
			CycleRecord record = CycleRecord.current();
			if (record != null) record.recordCall(call, outcome, millis);

			if (outcome.aborted()) {
				return new BatchResult(null, null, true);
			}

			if (outcome.appendResult()) {
				ACell result = (outcome.result() != null) ? outcome.result() : Maps.empty();
				sink.append(stamped(AbstractLLMAdapter.toolResultMessage(id, name, result)));
			}

			if (outcome.terminalStatus() != null) {
				terminalStatus = outcome.terminalStatus();
				terminalValue = outcome.terminalValue();
			}
		}

		return new BatchResult(terminalStatus, terminalValue, false);
	}

	/**
	 * Recognises a control tool emitted as assistant text rather than a native
	 * tool call. The accepted names are supplied by the active harness palette,
	 * so the fallback cannot accidentally turn ordinary prose into control flow.
	 */
	static AMap<AString, ACell> recogniseTextualControlCall(ACell assistantMessage,
			int iteration, Set<String> controlNames) {
		AString content = RT.ensureString(RT.getIn(assistantMessage, AbstractLLMAdapter.K_CONTENT));
		if (content == null || controlNames == null || controlNames.isEmpty()) return null;
		String text = content.toString().strip();

		String matched = null;
		for (String name : controlNames) {
			if (!text.startsWith(name)) continue;
			if (text.length() > name.length()) {
				char boundary = text.charAt(name.length());
				if (!(Character.isWhitespace(boundary) || boundary == ':' || boundary == '{')) continue;
			}
			matched = name;
			break;
		}
		if (matched == null) return null;

		String rest = text.substring(matched.length()).strip();
		if (rest.startsWith(":")) rest = rest.substring(1).strip();
		ACell arguments;
		if (rest.isEmpty()) {
			arguments = Maps.empty();
		} else if (rest.startsWith("{")) {
			try {
				arguments = convex.core.util.JSON.parse(rest);
			} catch (Exception e) {
				return null;
			}
		} else {
			return null;
		}

		AMap<AString, ACell> toolCall = Maps.of(
			AbstractLLMAdapter.K_ID, Strings.create("text-fallback-" + iteration),
			AbstractLLMAdapter.K_NAME, Strings.create(matched),
			AbstractLLMAdapter.K_ARGUMENTS, arguments);
		return Maps.of(
			AbstractLLMAdapter.K_ROLE, AbstractLLMAdapter.ROLE_ASSISTANT,
			AbstractLLMAdapter.K_CONTENT, content,
			AbstractLLMAdapter.K_TOOL_CALLS, Vectors.of(toolCall));
	}

	@SuppressWarnings("unchecked")
	private static AMap<AString, ACell> stamped(AMap<AString, ACell> message) {
		return (AMap<AString, ACell>) AbstractLLMAdapter.stampTs(message);
	}

	private static String describe(Throwable failure) {
		Throwable cause = AbstractLLMAdapter.unwrap(failure);
		String message = cause.getMessage();
		return (message == null || message.isBlank())
			? cause.getClass().getSimpleName() : message;
	}
}

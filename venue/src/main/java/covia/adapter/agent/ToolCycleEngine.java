package covia.adapter.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
 * <p><b>Concurrency.</b> A batch runs as ordered <i>waves</i>. Ordinary
 * operation calls — everything the registry sends to its fallback — that sit
 * next to each other in the batch run concurrently, one virtual thread each,
 * so the parallel reads and writes a model emits in one reply overlap instead
 * of queueing. A harness tool (anything registered by name: complete, fail,
 * subgoal, compact, context and skill loads, more_tools) is a barrier: it runs
 * alone, on the calling thread, after the wave before it has finished and
 * before the wave after it starts, because it mutates the harness context or
 * ends the cycle and its position in the batch is its meaning. Results are
 * appended in call order whatever order they finish in; a successful terminal
 * call still fences every later call; and cycle recording stays on the calling
 * thread, whose {@link CycleRecord} is thread-local.</p>
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

	/**
	 * Name-to-handler registry with one fallback for ordinary operation tools.
	 * A name with its own handler is a harness tool — a barrier in a batch;
	 * everything else is an operation call and may run alongside its
	 * neighbours.
	 */
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

		/** True for a tool the harness handles itself. */
		boolean isHarnessTool(String name) {
			return handlers.containsKey(name);
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

	/** A decoded call, with an argument-parse failure (if any) as its outcome. */
	private record Decoded(ToolCall call, ToolOutcome early) {}

	/** A call that reached its handler: the outcome and wall-clock millis. */
	private record Executed(ToolCall call, ToolOutcome outcome, long millis) {}

	/**
	 * Executes one provider tool-call batch with the shared wire invariants —
	 * see the class comment for the wave model. Every call is decoded first,
	 * so a malformed argument string costs its own call an error result and
	 * nothing else.
	 */
	@SuppressWarnings("unchecked")
	static <C> BatchResult executeBatch(AVector<ACell> toolCalls, int iteration,
			Registry<C> registry, C context, BatchSink sink, Logger log) {
		List<Decoded> decoded = new ArrayList<>();
		for (long i = 0; i < toolCalls.count(); i++) {
			decoded.add(decode(toolCalls.get(i), iteration, log));
		}

		String terminalStatus = null;
		ACell terminalValue = null;
		int i = 0;
		while (i < decoded.size()) {
			// The next wave: one harness tool alone, or every adjacent operation call.
			int end = i + 1;
			if (!registry.isHarnessTool(decoded.get(i).call().name())) {
				while (end < decoded.size() && !registry.isHarnessTool(decoded.get(end).call().name())) end++;
			}
			List<Decoded> wave = decoded.subList(i, end);
			i = end;

			// Provider protocols require a result for every call in a parallel
			// batch, even though later side effects must be fenced after terminal.
			if (terminalStatus != null) {
				for (Decoded d : wave) {
					sink.append(stamped(AbstractLLMAdapter.toolResultMessage(d.call().id(), d.call().name(),
						Strings.create("Error: not executed — an earlier call in this tool batch was terminal ("
							+ terminalStatus + ")."))));
				}
				continue;
			}

			// The common case — one call — stays on this thread: no hop, and a
			// nested harness sees the same thread-locals it always did.
			List<Executed> results = (wave.size() == 1)
				? List.of(execute(wave.get(0), registry, context, log))
				: executeConcurrently(wave, registry, context, log);

			for (Executed e : results) {
				sink.recordCall(e.call(), e.outcome(), e.millis());
				CycleRecord record = CycleRecord.current();
				if (record != null) record.recordCall(e.call(), e.outcome(), e.millis());

				if (e.outcome().aborted()) {
					return new BatchResult(null, null, true);
				}

				if (e.outcome().appendResult()) {
					ACell result = (e.outcome().result() != null) ? e.outcome().result() : Maps.empty();
					sink.append(stamped(AbstractLLMAdapter.toolResultMessage(e.call().id(), e.call().name(), result)));
				}

				if (e.outcome().terminalStatus() != null) {
					terminalStatus = e.outcome().terminalStatus();
					terminalValue = e.outcome().terminalValue();
				}
			}
		}

		return new BatchResult(terminalStatus, terminalValue, false);
	}

	private static Decoded decode(ACell rawCall, int iteration, Logger log) {
		AString id = RT.ensureString(RT.getIn(rawCall, AbstractLLMAdapter.K_ID));
		AString nameCell = RT.ensureString(RT.getIn(rawCall, AbstractLLMAdapter.K_NAME));
		String name = (nameCell != null) ? nameCell.toString() : "unknown";
		ACell toolInput = null;
		ToolOutcome early = null;
		try {
			toolInput = AbstractLLMAdapter.parseToolArguments(
				RT.getIn(rawCall, AbstractLLMAdapter.K_ARGUMENTS));
		} catch (IllegalArgumentException e) {
			String detail = describe(e);
			early = ToolOutcome.result(Strings.create("Error: " + detail));
			log.warn("Tool call {} has malformed arguments: {}", name, detail);
		}
		return new Decoded(new ToolCall(id, name, toolInput, iteration), early);
	}

	/** One call through its handler; every failure becomes an error outcome. */
	private static <C> Executed execute(Decoded d, Registry<C> registry, C context, Logger log) {
		long started = System.nanoTime();
		ToolOutcome outcome = d.early();
		if (outcome == null) {
			try {
				outcome = registry.dispatch(d.call(), context);
				if (outcome == null) {
					outcome = ToolOutcome.result(Strings.create(
						"Error: tool handler returned no outcome: " + d.call().name()));
				}
			} catch (Exception e) {
				String detail = describe(e);
				outcome = ToolOutcome.result(Strings.create("Error: " + detail));
				log.warn("Tool execution failed: {} — {}", d.call().name(), detail);
			}
		}
		return new Executed(d.call(), outcome, (System.nanoTime() - started) / 1_000_000);
	}

	/**
	 * A wave of operation calls, one virtual thread each, joined in call order.
	 * Each call bounds its own wall clock (the dispatch timeout), so the wave
	 * takes as long as its slowest member rather than the sum.
	 */
	private static <C> List<Executed> executeConcurrently(List<Decoded> wave,
			Registry<C> registry, C context, Logger log) {
		List<CompletableFuture<Executed>> futures = new ArrayList<>(wave.size());
		for (Decoded d : wave) {
			CompletableFuture<Executed> future = new CompletableFuture<>();
			Thread.ofVirtual().name("tool-call-" + d.call().name()).start(() -> {
				try {
					future.complete(execute(d, registry, context, log));
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});
			futures.add(future);
		}
		List<Executed> out = new ArrayList<>(wave.size());
		for (int k = 0; k < wave.size(); k++) {
			ToolCall call = wave.get(k).call();
			try {
				out.add(futures.get(k).get());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				out.add(new Executed(call, ToolOutcome.result(Strings.create(
					"Error: interrupted while waiting for " + call.name())), 0));
			} catch (ExecutionException e) {
				// execute() turns handler exceptions into outcomes; only an Error
				// escapes to here. Report it like any other failed call.
				String detail = describe((e.getCause() != null) ? e.getCause() : e);
				log.warn("Tool call {} failed outside its handler: {}", call.name(), detail);
				out.add(new Executed(call, ToolOutcome.result(Strings.create("Error: " + detail)), 0));
			}
		}
		return out;
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

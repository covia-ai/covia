package covia.adapter.agent;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.Utils;
import covia.api.Fields;
import covia.exception.JobFailedException;

/**
 * The record of one cycle's exchange with the model — what the timeline
 * entry keeps (AGENT_LOOP.md §2.4, #392). Per frame: its standing context and
 * the tools it was offered; per inference: what was newly sent, the reply
 * verbatim (or the failure), and the tool batch it requested; a subgoal call
 * carries its child frame's record in the same shape.
 *
 * <p><b>One rule.</b> A message is recorded once, in the inference that first
 * sends it — except turns of the root conversation, which the session
 * records. The bands the assembler marks make this mechanical: head and live
 * messages are recorded when first seen (a frame's first inference pulls them
 * up as its {@code context}); the conversation band is the session's for the
 * root frame and recorded once, when the frame opens, for a child; the tool
 * loop band is this cycle's replies and results, recorded under their
 * inferences; the tail is recorded as it appears.</p>
 *
 * <p>Thread-confined: a cycle and every inference it makes — tool-loop
 * iterations, subgoal recursion — run on one virtual thread, so nothing is
 * threaded through the loops. Nested agent transitions run on their own
 * threads and never share a record.</p>
 */
public final class CycleRecord {

	private static final ThreadLocal<CycleRecord> CURRENT = new ThreadLocal<>();

	/** What a closed record hands back: the root frame's record and the
	 *  cycle's token totals — the sum over every reply, child frames
	 *  included — or null when no reply reported usage. */
	public record Result(AMap<AString, ACell> cycle, AMap<AString, ACell> tokens) {}

	/** One frame's record. */
	private static final class Frame {
		AVector<ACell> context = Vectors.empty();
		AVector<ACell> tools;
		AVector<ACell> inferences = Vectors.empty();
		AVector<ACell> lastTools;
		AMap<AString, ACell> open;
		long openedAt;

		AMap<AString, ACell> toCell() {
			AMap<AString, ACell> m = Maps.of(Fields.CONTEXT, context, Fields.INFERENCES, inferences);
			if (tools != null) m = m.assoc(Fields.TOOLS, tools);
			return m;
		}
	}

	private final Set<ACell> sent = new HashSet<>();
	private final ArrayDeque<Frame> frames = new ArrayDeque<>();
	private final Map<AString, AMap<AString, ACell>> children = new HashMap<>();
	/** input, output, total, measured, cacheRead, cacheWrite */
	private final long[] tally = new long[6];

	private CycleRecord() {
		frames.push(new Frame());
	}

	/** Opens the record for the cycle running on this thread. */
	public static void begin() {
		CURRENT.set(new CycleRecord());
	}

	/** The open record, or null outside a cycle. */
	static CycleRecord current() {
		return CURRENT.get();
	}

	/** Closes the record; null when none is open. */
	public static Result end() {
		CycleRecord r = CURRENT.get();
		if (r == null) return null;
		CURRENT.remove();
		return new Result(r.frames.peekLast().toCell(), r.tokens());
	}

	/** Adds a reply's provider-reported usage to the open record's totals, if any. */
	public static void tally(ACell reply) {
		CycleRecord r = CURRENT.get();
		if (r != null) r.add(reply);
	}

	// ========== Frames ==========

	/** A child frame starts: its inferences record under it until it closes. */
	void openFrame() {
		frames.push(new Frame());
	}

	/** Closes the innermost child frame and returns its record; the root never closes this way. */
	AMap<AString, ACell> closeFrame() {
		return (frames.size() > 1) ? frames.pop().toCell() : null;
	}

	/** A closed child record waits here for the call that produced it. */
	void attachFrame(AString callId, AMap<AString, ACell> frame) {
		if (callId != null && frame != null) children.put(callId, frame);
	}

	// ========== Inferences ==========

	/** Before a call: what this inference newly sends, by the bands the prompt marks. */
	void beginInference(ContextAssembler.Prompt prompt, AString op, AString model) {
		Frame f = frames.peek();
		boolean first = f.inferences.isEmpty();
		boolean child = frames.size() > 1;
		Map<ContextAssembler.Band, Integer> marks = prompt.marks();
		int liveEnd = end(marks, ContextAssembler.Band.LIVE);
		int conversationEnd = end(marks, ContextAssembler.Band.CONVERSATION);
		int loopEnd = end(marks, ContextAssembler.Band.TOOL_LOOP);
		AVector<ACell> context = Vectors.empty();
		AVector<ACell> newly = Vectors.empty();
		AVector<ACell> messages = prompt.messages();
		for (int i = 0; i < messages.count(); i++) {
			ACell m = messages.get(i);
			if (i < liveEnd) {
				if (!sent.add(m)) continue;
				if (first) context = context.conj(m); else newly = newly.conj(m);
			} else if (i < conversationEnd) {
				// The root conversation is the session's; a child's is recorded
				// once, as the frame opens — its goal and the ancestor rendering.
				if (child && first && sent.add(m)) newly = newly.conj(m);
			} else if (i < loopEnd) {
				// This cycle's replies and tool results: recorded under their inferences.
			} else if (sent.add(m)) {
				newly = newly.conj(m);
			}
		}
		if (first) f.context = context;
		AVector<ACell> tools = toolNames(prompt.tools());
		AMap<AString, ACell> inference = Maps.of(
			Fields.TS, CVMLong.create(Utils.getCurrentTimestamp()),
			Fields.OP, op);
		if (model != null) inference = inference.assoc(AbstractLLMAdapter.K_MODEL, model);
		if (!newly.isEmpty()) inference = inference.assoc(Fields.SENT, newly);
		if (first) {
			f.tools = tools;
		} else if (!tools.equals(f.lastTools)) {
			inference = inference.assoc(Fields.TOOLS, tools);
		}
		f.lastTools = tools;
		f.open = inference;
		f.openedAt = System.nanoTime();
	}

	/** After the call: the reply verbatim. */
	void endInference(ACell reply) {
		Frame f = frames.peek();
		if (f.open == null) return;
		f.inferences = f.inferences.conj(f.open.assoc(Fields.MS, elapsed(f)).assoc(Fields.REPLY, reply));
		f.open = null;
		add(reply);
	}

	/** A call that produced no reply. */
	void failInference(String error) {
		Frame f = frames.peek();
		if (f.open == null) return;
		f.inferences = f.inferences.conj(
			f.open.assoc(Fields.MS, elapsed(f)).assoc(Fields.ERROR, Strings.create(error)));
		f.open = null;
	}

	/** From the tool batch that followed the frame's last inference. */
	void recordCall(ToolCycleEngine.ToolCall call, ToolCycleEngine.ToolOutcome outcome, long ms) {
		Frame f = frames.peek();
		long n = f.inferences.count();
		if (n == 0) return;
		AMap<AString, ACell> rec = Maps.of(
			AbstractLLMAdapter.K_NAME, Strings.create(call.name()),
			Fields.MS, CVMLong.create(ms));
		if (call.id() != null) rec = rec.assoc(AbstractLLMAdapter.K_ID, call.id());
		ACell result = outcome.result();
		if (result != null) {
			rec = rec.assoc(Fields.RESULT, result);
			if (AbstractLLMAdapter.toolFailureMessage(result) != null
					|| CVMBool.TRUE.equals(RT.getIn(result, AbstractLLMAdapter.K_IS_ERROR))) {
				rec = rec.assoc(AbstractLLMAdapter.K_IS_ERROR, CVMBool.TRUE);
			}
		}
		AMap<AString, ACell> frame = (call.id() != null) ? children.remove(call.id()) : null;
		if (frame != null) rec = rec.assoc(Fields.FRAME, frame);
		AMap<AString, ACell> last = RT.ensureMap(f.inferences.get(n - 1));
		AVector<ACell> calls = RT.ensureVector(last.get(Fields.CALLS));
		calls = ((calls != null) ? calls : Vectors.empty()).conj(rec);
		f.inferences = f.inferences.assoc(n - 1, last.assoc(Fields.CALLS, calls));
	}

	// ========== Tokens (#217) ==========

	/** Adds a reply's {@code tokens} to the tally. Missing counts contribute
	 *  nothing; a missing total is derived from input + output so the
	 *  invariant total ≥ input + output holds across providers that omit it. */
	private void add(ACell reply) {
		ACell tokens = RT.getIn(reply, Fields.TOKENS);
		if (!(tokens instanceof AMap)) return;
		CVMLong in  = RT.ensureLong(RT.getIn(tokens, Fields.INPUT));
		CVMLong out = RT.ensureLong(RT.getIn(tokens, Fields.OUTPUT));
		CVMLong tot = RT.ensureLong(RT.getIn(tokens, Fields.TOTAL));
		if (in == null && out == null && tot == null) return;
		long inV = (in != null) ? in.longValue() : 0;
		long outV = (out != null) ? out.longValue() : 0;
		tally[0] += inV;
		tally[1] += outV;
		tally[2] += (tot != null) ? tot.longValue() : inV + outV;
		tally[3] = 1;
		CVMLong read = RT.ensureLong(RT.getIn(tokens, Fields.CACHE_READ));
		CVMLong write = RT.ensureLong(RT.getIn(tokens, Fields.CACHE_WRITE));
		if (read != null) tally[4] += read.longValue();
		if (write != null) tally[5] += write.longValue();
	}

	/** The cycle's totals, or null when no reply reported usage — absent
	 *  means "not measured", never zero. Cache counts only when reported. */
	private AMap<AString, ACell> tokens() {
		if (tally[3] == 0) return null;
		AMap<AString, ACell> totals = Maps.of(
			Fields.INPUT,  CVMLong.create(tally[0]),
			Fields.OUTPUT, CVMLong.create(tally[1]),
			Fields.TOTAL,  CVMLong.create(tally[2]));
		if (tally[4] > 0) totals = totals.assoc(Fields.CACHE_READ, CVMLong.create(tally[4]));
		if (tally[5] > 0) totals = totals.assoc(Fields.CACHE_WRITE, CVMLong.create(tally[5]));
		return totals;
	}

	// ========== Failure carrier ==========

	/**
	 * A transition failure carrying the record of what ran before it, so the
	 * cycle's entry keeps its inferences. The message is the original's and
	 * the original is the cause; the run loop unwraps it.
	 */
	public static final class Failure extends JobFailedException {
		private static final long serialVersionUID = 1L;
		private final transient Result result;

		private Failure(RuntimeException cause, Result result) {
			super((cause.getMessage() != null) ? cause.getMessage() : cause.toString());
			initCause(cause);
			this.result = result;
		}

		public Result result() { return result; }

		/** Wraps when a record is open on this thread; otherwise the failure passes through. */
		public static RuntimeException of(RuntimeException e) {
			if (e instanceof Failure) return e;
			Result r = end();
			return (r == null) ? e : new Failure(e, r);
		}

		/** The carrier in a cause chain, or null. */
		public static Failure find(Throwable t) {
			for (; t != null; t = t.getCause()) {
				if (t instanceof Failure f) return f;
			}
			return null;
		}
	}

	// ========== Helpers ==========

	private static int end(Map<ContextAssembler.Band, Integer> marks, ContextAssembler.Band band) {
		Integer v = marks.get(band);
		return (v != null) ? v : 0;
	}

	private static CVMLong elapsed(Frame f) {
		return CVMLong.create((System.nanoTime() - f.openedAt) / 1_000_000);
	}

	private static AVector<ACell> toolNames(AVector<ACell> defs) {
		AVector<ACell> names = Vectors.empty();
		for (long i = 0; defs != null && i < defs.count(); i++) {
			AString name = RT.ensureString(RT.getIn(defs.get(i), AbstractLLMAdapter.K_NAME));
			if (name != null) names = names.conj(name);
		}
		return names;
	}
}

package covia.adapter.agent;

/**
 * Marker for LLM transition adapters that own the session's frame stack and
 * pending drain. Both built-in runtimes claim the session through the shared
 * {@link FrameStore} and write every conversation mutation live.
 *
 * <p>The framework consults this on the <em>adapter</em>, not the transition
 * output: an errored or cancelled transition produces no output, so gating on
 * output shape would double-drain pending and re-append turns the adapter
 * already wrote. When the transition op's adapter carries this marker, the
 * run-loop merge skips its own frames write, turn append, and pending drain
 * for the picked session; everything else (timeline, task removal, status,
 * {@code inCycle} clear) is unchanged.</p>
 */
public interface FramesOwning {
}

package covia.adapter.agent;

/**
 * Marker: this transition adapter owns the session's frame stack and pending
 * drain itself (lattice-resident frames — it claims the session via
 * {@code beginSessionCycle} and writes every frame mutation live).
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

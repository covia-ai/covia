package covia.adapter.agent;

/**
 * Marker for transition adapters that write their turns into the session's
 * frame stack live, through the shared {@link FrameStore}, under the claim the
 * run loop made when the cycle took its input. For these adapters the run-loop
 * merge appends none of the transition's turns; everything else (the cycle's
 * input, timeline, task removal, status, the end of the claim) is the same.
 *
 * <p>The framework consults this on the <em>adapter</em>, not the transition
 * output: an errored or cancelled transition produces no output, so gating on
 * output shape would re-append turns the adapter already wrote.</p>
 */
public interface FramesOwning {
}

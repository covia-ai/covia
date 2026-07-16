package covia.lattice;

import convex.core.data.ACell;
import convex.core.data.AString;

/**
 * Evaluates a capability gate (#216): a grant may carry {@code nb: {gate:
 * "v/ops/…"}} — a reference to an operation that decides, per invocation,
 * whether the capability applies. The gate op receives {@code {operation,
 * input, caller}} describing the invocation being authorised; the capability
 * is valid iff the gate op succeeds. Arbitrary policy logic without a policy
 * language: a gate can be a three-line checker, an orchestration, or a
 * lattice lookup — always an auditable, content-addressed operation.
 *
 * <p>Implementations are installed on the {@link covia.venue.RequestContext}
 * by the dispatch layer (JobManager — the one place that has the engine, the
 * operation reference and the invocation input together). A context without
 * an evaluator treats gated grants as unable to authorise (fail-closed);
 * ungated grants are unaffected.</p>
 */
@FunctionalInterface
public interface CapabilityGate {

	/**
	 * Evaluates a gate operation against the invocation being authorised.
	 *
	 * @param gateOp   the gate operation reference from the grant's {@code nb.gate}
	 * @param opRef    the operation reference being invoked, as the caller
	 *                 supplied it (may be null for metadata-direct dispatch)
	 * @param input    the invocation input the gate rules on
	 * @param caller   the caller's DID
	 * @return {@code null} when the gate passes; otherwise a denial detail
	 *         suitable for inclusion in the capability-denied message
	 */
	String evaluate(AString gateOp, AString opRef, ACell input, AString caller);
}

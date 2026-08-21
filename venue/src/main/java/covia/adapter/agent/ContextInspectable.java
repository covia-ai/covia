package covia.adapter.agent;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import covia.venue.RequestContext;

/**
 * Adapter capability: assemble the exact context the adapter would send to
 * its model for one hypothetical call, without invoking the model.
 * {@code agent:context} dispatches through this interface, so the
 * presentation of a context is owned by the adapter that builds it.
 */
public interface ContextInspectable {

	/**
	 * What the hypothetical call carries. Null fields mean "none": no
	 * session, no inbox, no pending results, no task — a wake-up.
	 *
	 * @param config the agent's record-level config
	 * @param state the agent's persisted state
	 * @param session the session record, so its conversation renders exactly as a live transition would see it
	 * @param messages inbox envelopes ({@code {message, caller?}}) or plain strings arriving this cycle
	 * @param pending job results arriving this cycle
	 * @param task a task input the agent would have to complete or fail
	 */
	record Inspection(AMap<AString, ACell> config, ACell state, AMap<AString, ACell> session,
			AVector<ACell> messages, AVector<ACell> pending, ACell task) {}

	/**
	 * The assembled context for the call: the level-3 input — {@code model},
	 * {@code messages}, {@code tools}, {@code cacheMarks} — plus the assembly
	 * diagnostics {@code budget} ({@code bytes}, {@code used}, {@code remaining}),
	 * {@code marks} (message counts at each band's end) and {@code labels}.
	 */
	AMap<AString, ACell> inspectContext(Inspection inspection, RequestContext ctx);
}

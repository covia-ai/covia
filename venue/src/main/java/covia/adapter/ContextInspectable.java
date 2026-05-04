package covia.adapter;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import covia.venue.RequestContext;

/**
 * Adapter capability: render the exact context the adapter would assemble
 * for a fresh agent transition, as a pretty-printed JSON string. Returns
 * what the underlying engine would receive, without invoking it.
 *
 * <p>Implemented by Level 2 transition adapters that build complex per-turn
 * context (system prompt, conversation, tools, etc.) — typically the
 * LLM-backed agent adapters. {@code agent:context} dispatches via this
 * interface so its presentation logic is owned by the adapter that builds
 * the context, not by {@code AgentAdapter}.</p>
 */
public interface ContextInspectable {

	/**
	 * Renders the context for a fresh transition as pretty-printed JSON.
	 *
	 * @param recordConfig the agent's record-level config (may be null)
	 * @param state the agent's persisted state (may be null)
	 * @param taskInput optional task input — if non-null, the goal user
	 *        message that would appear on the first iteration is synthesised
	 *        and included
	 * @param ctx request context (caller identity, capabilities)
	 * @return pretty-printed JSON string of the assembled transition input
	 */
	AString inspectContext(AMap<AString, ACell> recordConfig,
	                       ACell state,
	                       ACell taskInput,
	                       RequestContext ctx);
}

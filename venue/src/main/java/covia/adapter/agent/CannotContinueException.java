package covia.adapter.agent;

import covia.exception.CoviaException;

/**
 * The agent cannot continue: the setup its model call runs under is wrong (a
 * key, model or setting), or its provider cannot be reached. Every unit of work
 * the agent took next would fail the same way, so a transition raises this
 * instead of failing the work, and the run loop suspends the agent and keeps
 * its queue until the cause is fixed and the agent resumed (AGENT_LOOP.md
 * §3.4). The message is the reason, in the venue's words; the cause is the
 * failure it was decided from.
 */
@SuppressWarnings("serial")
public class CannotContinueException extends CoviaException {

	public CannotContinueException(String message, Throwable cause) {
		super(message, cause);
	}

	/** The first one in {@code t}'s cause chain, or null. */
	public static CannotContinueException find(Throwable t) {
		for (; t != null; t = t.getCause()) {
			if (t instanceof CannotContinueException c) return c;
		}
		return null;
	}
}

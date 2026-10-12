package covia.adapter.remote;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/**
 * A live binding to externally controlled agent work. Construction is passive;
 * the adapter binds cancellation before starting work. Transport, external IDs,
 * credential handling and recovery remain provider responsibilities.
 */
public interface RemoteInteraction {
	String MESSAGE_UNSUPPORTED = "This interaction does not accept messages";

	/**
	 * Accept a job message, or throw if it cannot be accepted. A successful
	 * return need not mean execution finished. Unknown remote acknowledgement
	 * must be reported, never silently converted to success or blindly retried.
	 */
	default void deliver(AMap<AString, ACell> messageRecord) {
		throw new UnsupportedOperationException(MESSAGE_UNSUPPORTED);
	}

	/** Request cancellation. Local cancellation does not prove remote cessation. */
	void cancel();
}

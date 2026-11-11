package covia.adapter.messaging;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/** A live messaging binding. Its transport and admission policy belong to the provider. */
public interface MessagingBot<S extends MessagingBotSpec> {
	enum Managed { CONFIG, RUNTIME }

	S spec();
	Managed managed();
	/** Starts the binding's transport; it may return before the transport is connected. */
	void start();
	/**
	 * Stops the binding for good. The adapter's lock, and on a disable the
	 * engine's, is held meanwhile, so this returns promptly: it aborts a
	 * connection attempt in progress rather than waiting for it.
	 */
	void stop();
	AMap<AString, ACell> status();
}

package covia.adapter.messaging;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/** A live messaging binding. Its transport and admission policy belong to the provider. */
public interface MessagingBot<S extends MessagingBotSpec> {
	enum Managed { CONFIG, RUNTIME }

	S spec();
	Managed managed();
	void start();
	void stop();
	AMap<AString, ACell> status();
}

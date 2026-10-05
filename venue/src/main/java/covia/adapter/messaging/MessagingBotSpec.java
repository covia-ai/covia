package covia.adapter.messaging;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import covia.venue.Engine;

/** Provider-independent identity and routing of a messaging binding. */
public interface MessagingBotSpec {
	String name();
	String userRef();
	String agent();
	String operation();
	ACell reply();

	String PUBLIC_ACCESS_DISABLED = " acts as public but public access is disabled";

	default AString userDID(Engine engine) {
		if (!"public".equals(userRef())) return Strings.create(userRef());
		if (engine == null || !engine.config().isPublicAccess()) {
			throw new IllegalStateException("bot '" + name() + "'" + PUBLIC_ACCESS_DISABLED);
		}
		return Strings.create(engine.getDIDString() + ":public");
	}

	default boolean routesToAgent() { return agent() != null; }
	default boolean routesToOperation() { return operation() != null; }
	default String target() { return routesToAgent() ? "agent " + agent() : "operation " + operation(); }
	default boolean silent() { return CVMBool.FALSE.equals(reply()); }
	default String fixedReply() { return reply() instanceof AString s ? s.toString() : null; }
}

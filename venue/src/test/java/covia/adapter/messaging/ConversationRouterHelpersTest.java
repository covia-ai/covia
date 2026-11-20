package covia.adapter.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import convex.core.data.Maps;
import covia.adapter.AgentAdapter;
import covia.api.Fields;
import covia.exception.JobFailedException;
import covia.grid.Job;
import covia.grid.Status;

/**
 * {@link ConversationRouter}'s helpers, called directly. Conversations routed
 * through a venue are in {@link ConversationRouterTest}.
 */
class ConversationRouterHelpersTest {

	@Test void recoveryUsesOnlyTheAgentCodeRegardlessOfWording() {
		Job missing = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		missing.fail(AgentAdapter.UNKNOWN_SESSION_CODE, "This conversation has expired");
		assertTrue(ConversationRouter.isUnknownSession(new CompletionException(new JobFailedException(missing))));
		assertFalse(ConversationRouter.isUnknownSession(new JobFailedException("Unknown session")));
		Job other = Job.create(Maps.of(Fields.STATUS, Status.STARTED));
		other.fail("other:unknown-session", "Unknown session");
		assertFalse(ConversationRouter.isUnknownSession(new JobFailedException(other)));
	}
}

package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.Index;
import convex.core.data.Keyword;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.lattice.Covia;

/** A component moved onto other storage keeps its configuration and changes only that storage. */
public class ComponentRebindTest {
	private static final AString DID = Strings.create("did:key:rebind");
	private static final SecretStore.Keys NO_SECRETS = SecretStore.keys(AKeyPair.generate());

	private final AtomicLong clock = new AtomicLong(1000);
	private final User user = venue().users().ensure(DID);
	private final Agent agent = user.ensureAgent("helper", null, null);

	private VenueState venue() {
		AKeyPair kp = AKeyPair.generate();
		var root = Cursors.root(Index.<Keyword, ACell>none());
		var context = VenueContext.create(() -> CVMLong.create(clock.get()));
		VenueState venue = VenueState.fromRoot(root, kp.getAccountKey(), kp::signData, context);
		venue.initialise(DID, NO_SECRETS);
		return venue;
	}

	@Test void reboundAgentKeepsItsIdentityAndStampsItsNewStorage() {
		ACell before = agent.value();
		Agent rebound = agent.rebind(Cursors.root(before));
		assertEquals(agent.getOwner(), rebound.getOwner());
		assertEquals(agent.getAgentId(), rebound.getAgentId());

		clock.set(2000);
		rebound.setStatus(Agent.SUSPENDED);
		assertEquals(Agent.SUSPENDED, rebound.getStatus());
		// Stamping again at the same time changes nothing only if this edit was stamped then.
		assertEquals(rebound.value(), Covia.stampAgent(rebound.value(), CVMLong.create(2000)));
		assertEquals(before, agent.value(), "the original storage is untouched");
	}

	@Test void reboundUserMakesItsChildrenOnItsNewStorage() {
		User rebound = user.rebind(Cursors.root(user.value()));
		rebound.ensureAgent("other", null, null);
		assertNotNull(rebound.agent("other"));
		assertNull(user.agent("other"), "the original user has no such agent");
	}

	@Test void forkedAgentPublishesItsEditsOnlyOnSync() {
		Agent fork = (Agent) agent.fork();
		fork.setStatus(Agent.SUSPENDED);
		assertEquals(Agent.SLEEPING, agent.getStatus());
		fork.sync();
		assertEquals(Agent.SUSPENDED, agent.getStatus());
	}

	@Test void transactionRerunsOnTheValueAConflictingWriteLeft() {
		List<AString> seen = new ArrayList<>();
		agent.execute(draft -> {
			seen.add(draft.getStatus());
			if (seen.size() == 1) agent.setStatus(Agent.SUSPENDED);
			draft.setStatus(Agent.TERMINATED);
		});
		assertEquals(List.of(Agent.SLEEPING, Agent.SUSPENDED), seen);
		assertEquals(Agent.TERMINATED, agent.getStatus());
	}
}

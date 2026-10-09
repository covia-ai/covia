package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.auth.did.DIDVerifier;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.UserAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.server.AuthMiddleware;

class UserDeletionTest {
	private static Engine engine;

	@BeforeAll static void setup() {
		// Bespoke admission and managed-user configuration, shared across this suite.
		engine = Engine.createTemp(Maps.of(Config.HOSTNAME, "delete.example",
			Config.USERS, Maps.of(Config.AUTO_CREATE, false)));
		Engine.addDemoAssets(engine);
	}

	@AfterAll static void close() { engine.close(); }

	private AString user() {
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		engine.getVenueState().users().create(did);
		return did;
	}

	private ACell delete(AString did) throws Exception {
		return engine.jobs().invokeInternal("v/ops/user/delete", Maps.of(Fields.DID, did),
			engine.venueContext()).get(5, TimeUnit.SECONDS);
	}

	@Test void selfAndOtherUsersHaveNoImplicitDeletionAuthority() {
		AString target = user();
		for (RequestContext ctx : new RequestContext[] {
			RequestContext.of(target), RequestContext.of(user()), RequestContext.ANONYMOUS,
			RequestContext.ofAgent(target, Strings.create("agent"))}) {
			assertThrows(AuthException.class, () -> engine.deleteUser(ctx, target));
		}
		assertNotNull(engine.getVenueState().users().get(target));
		assertThrows(IllegalArgumentException.class,
			() -> engine.deleteUser(engine.venueContext(), engine.getDIDString()));
		assertThrows(IllegalArgumentException.class, () -> engine.deleteUser(engine.venueContext(),
			Strings.create(engine.getDIDString() + ":public")));
	}

	@Test void venueDelegationCanAuthoriseDeletionIncludingSelf() throws Exception {
		AKeyPair delegate = AKeyPair.generate();
		AString did = UCAN.toDIDKey(delegate.getAccountKey());
		engine.getVenueState().users().create(did);
		AString proof = UCAN.create(engine.getKeyPair(), delegate.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600,
			Vectors.of(Capability.create(Strings.create(engine.getDIDString() + "/users"), Abilities.USER_DELETE)),
			Vectors.empty()).toJWT(engine.getKeyPair());
		RequestContext ctx = AuthMiddleware.withTransportGrants(RequestContext.of(did), Vectors.of(proof), DIDVerifier.CONVEX);
		ACell result = engine.jobs().invokeInternal("v/ops/user/delete", Maps.of(Fields.DID, did), ctx).get(5, TimeUnit.SECONDS);
		assertEquals(did, RT.getIn(result, "deletedBy"));
		assertNull(engine.getVenueState().users().get(did));
	}

	@Test void deletionErasesNamespaceCancelsJobsAndSchedulesAndIsIdempotent() throws Exception {
		AString did = user();
		RequestContext ctx = RequestContext.of(did);
		User retained = engine.getVenueState().users().get(did);
		retained.cursor().path(Strings.create("w")).set(Maps.of("private", "personal-data"));
		retained.secrets().store("credential", "secret-value", SecretStore.deriveKey(engine.getKeyPair()));
		retained.ensureAgent("old-agent", Maps.empty(), Strings.create("old-state"));
		Job pending = engine.jobs().invokeOperation(Strings.create("v/test/ops/never"), Maps.empty(), ctx);
		engine.gridScheduler().schedule(Strings.create("v/test/ops/echo"), Strings.create("queued-personal-data"),
			ctx, System.currentTimeMillis() + 86400000, null, false);
		ACell first = delete(did);
		assertEquals(Status.CANCELLED, pending.getStatus());
		assertNull(engine.getVenueState().users().get(did));
		assertFalse(engine.getVenueState().users().getAll().containsKey(did));
		assertTrue(engine.gridScheduler().list(ctx).isEmpty());
		assertThrows(IllegalArgumentException.class, () -> ((UserAdapter) engine.getAdapter("user"))
			.info(engine.venueContext(), Maps.of(Fields.DID, did)));
		assertThrows(AuthException.class, () -> engine.admitUser(did));
		assertThrows(AuthException.class, () -> retained.cursor().path(Strings.create("w")).set(Maps.of("late", true)));
		assertEquals(first, delete(did));

		// Explicit operator recreation follows normal provisioning, never an old job cursor.
		engine.jobs().invokeInternal("v/ops/user/create", Maps.of(Fields.DID, did), engine.venueContext()).get(5, TimeUnit.SECONDS);
		User fresh = engine.getVenueState().users().get(did);
		assertNotNull(fresh);
		assertTrue(fresh.getJobs().isEmpty());
		assertTrue(fresh.secrets().list().isEmpty());
		assertNull(fresh.getAgents());
		assertThrows(AuthException.class, () -> retained.cursor().path(Strings.create("w")).set(Maps.of("late", true)));
		pending.updateData(pending.getData().assoc(Fields.OUTPUT, Strings.create("late-output")));
		assertTrue(fresh.getJobs().isEmpty());
		assertNull(fresh.cursor().path(Strings.create("w")).get());
	}

	@Test void automaticAdmissionCanRecreateAnEmptyAccount() throws Exception {
		Engine open = TestEngine.ENGINE;
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		User old = open.admitUser(did);
		old.ensureAgent("removed", Maps.empty(), null);
		open.deleteUser(open.venueContext(), did);
		User fresh = open.admitUser(did);
		assertNull(fresh.getAgents());
		assertThrows(AuthException.class, old::get);
	}

	@Test void claimedScheduledWorkCannotRunInARecreatedAccount() throws Exception {
		for (boolean tracked : new boolean[] {true, false}) {
			AString did = user();
			RequestContext ctx = RequestContext.of(did);
			AMap<AString, ACell> input = Maps.of(Fields.PATH, "w/stale-schedule", Fields.VALUE, "old-input");
			JobManager.Prepared prepared = tracked
				? engine.jobs().prepareTracked(Strings.create("v/ops/covia/write"), input, ctx)
				: engine.jobs().prepareUntracked(Strings.create("v/ops/covia/write"), input, ctx);
			delete(did);
			engine.getVenueState().users().create(did);
			assertEquals(Status.CANCELLED, prepared.start().getStatus());
			assertNull(engine.getVenueState().users().get(did).cursor().path(Strings.create("w")).get());
		}
	}

	@Test void deletionCancelsStubAgentTransitionWithoutRestoringItsState() throws Exception {
		AString did = user();
		RequestContext ctx = RequestContext.of(did);
		String agentId = "erased-agent";
		try (var gate = covia.adapter.TestAdapter.createGate("delete-agent-gate")) {
			engine.jobs().invokeInternal("v/ops/agent/create", Maps.of(Fields.AGENT_ID, agentId,
				Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/taskcomplete", "testGate", "delete-agent-gate")),
				ctx).get(5, TimeUnit.SECONDS);
			Job request = engine.jobs().invokeOperation("v/ops/agent/request",
				Maps.of(Fields.AGENT_ID, agentId, Fields.INPUT, "work"), ctx);
			assertTrue(gate.awaitEntered(5, TimeUnit.SECONDS));
			delete(did);
			assertEquals(Status.CANCELLED, request.getStatus());
			engine.getVenueState().users().create(did);
			gate.release();
			assertNull(engine.getVenueState().users().get(did).getAgents());
			assertTrue(engine.getVenueState().users().get(did).getJobs().isEmpty());
		}
	}

	@Test void erasesProfileAndAliasesRevokesKeysAndOAuthReprovisionsEmptyAccount() throws Exception {
		AString id = Strings.create("oauth-erasure");
		AMap<AString, ACell> profile = Maps.of(Fields.EMAIL, "erase-profile@example.test", Fields.NAME, "Private Name",
			Fields.PROVIDER, "fake", Fields.PROVIDER_SUB, "private-provider-sub");
		ACell record = engine.getAuth().provisionLogin(id, profile);
		AString did = RT.ensureString(RT.getIn(record, Fields.DID));
		AString key = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		engine.getAuth().addAuthenticationKey(id, key, did, Strings.create("private-label"));
		User old = engine.getVenueState().users().get(did);
		old.ensureAgent("private-agent", Maps.empty(), null);
		delete(did);
		assertNull(engine.getAuth().getUser(id));
		assertFalse(engine.getAuth().isAuthenticationKeyActive(id, key));
		String directory = engine.getAuth().getUsers().toString();
		for (String pii : new String[] {"erase-profile@example.test", "Private Name", "private-provider-sub", "private-label"}) {
			assertFalse(directory.contains(pii));
		}
		ACell replacement = engine.getAuth().provisionLogin(id, profile);
		assertEquals(did, RT.getIn(replacement, Fields.DID));
		assertNull(engine.getVenueState().users().get(did).getAgents());
		assertFalse(engine.getAuth().isAuthenticationKeyActive(id, key));
		assertThrows(AuthException.class, old::get);
	}
}

package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.lang.RT;
import covia.adapter.UserAdapter;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.exception.InvalidInputException;
import covia.exception.NotFoundException;
import covia.grid.Job;
import covia.grid.Status;
import covia.lattice.Covia;

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
		assertNotNull(engine.getVenueState().users().user(target));
		assertThrows(InvalidInputException.class,
			() -> engine.deleteUser(engine.venueContext(), engine.getDIDString()));
		assertThrows(InvalidInputException.class, () -> engine.deleteUser(engine.venueContext(),
			Strings.create(engine.getDIDString() + ":public")));
	}

	@Test void venueDelegationCanAuthoriseDeletionIncludingSelf() throws Exception {
		AKeyPair delegate = AKeyPair.generate();
		AString did = UCAN.toDIDKey(delegate.getAccountKey());
		engine.getVenueState().users().create(did);
		AString proof = UCAN.create(engine.getKeyPair(), delegate.getAccountKey(),
			System.currentTimeMillis() / 1000 + 3600,
			Vectors.of(Capability.create(Strings.create(engine.getDIDString() + "/users"), Abilities.ADMIN_USER_DELETE)),
			Vectors.empty()).toJWT(engine.getKeyPair());
		RequestContext ctx = engine.withTransportGrants(RequestContext.of(did), Vectors.of(proof));
		ACell result = engine.jobs().invokeInternal("v/ops/user/delete", Maps.of(Fields.DID, did), ctx).get(5, TimeUnit.SECONDS);
		assertEquals(did, RT.getIn(result, "deletedBy"));
		assertNull(engine.getVenueState().users().user(did));
	}

	@Test void deletionErasesNamespaceCancelsJobsAndSchedulesAndIsIdempotent() throws Exception {
		AString did = user();
		RequestContext ctx = RequestContext.of(did);
		User retained = engine.getVenueState().users().user(did);
		Covia.entry(retained.cursor(), Strings.create("w")).set(Maps.of("private", "personal-data"));
		retained.secrets().store("credential", "secret-value", SecretStore.keys(engine.getKeyPair()));
		TestAgents.ensure(retained, "old-agent", Maps.empty(), Strings.create("old-state"));
		Job pending = engine.jobs().invokeOperation(Strings.create("v/test/ops/never"), Maps.empty(), ctx);
		engine.gridScheduler().schedule(Strings.create("v/test/ops/echo"), Strings.create("queued-personal-data"),
			ctx, System.currentTimeMillis() + 86400000, null, false);
		ACell first = delete(did);
		assertEquals(Status.CANCELLED, pending.getStatus());
		assertNull(engine.getVenueState().users().user(did));
		assertFalse(engine.getVenueState().users().getAll().containsKey(did));
		assertTrue(engine.gridScheduler().list(ctx).isEmpty());
		assertThrows(NotFoundException.class, () -> ((UserAdapter) engine.getAdapter("user"))
			.info(engine.venueContext(), Maps.of(Fields.DID, did)));
		assertThrows(AuthException.class, () -> engine.admitUser(did));
		assertThrows(AuthException.class, () -> Covia.entry(retained.cursor(), Strings.create("w")).set(Maps.of("late", true)));
		assertEquals(first, delete(did));

		// Explicit operator recreation follows normal provisioning, never an old job cursor.
		engine.jobs().invokeInternal("v/ops/user/create", Maps.of(Fields.DID, did), engine.venueContext()).get(5, TimeUnit.SECONDS);
		User fresh = engine.getVenueState().users().user(did);
		assertNotNull(fresh);
		assertTrue(fresh.jobs().getAll().isEmpty());
		assertTrue(fresh.secrets().list().isEmpty());
		assertTrue(fresh.agents().all().isEmpty());
		assertThrows(AuthException.class, () -> Covia.entry(retained.cursor(), Strings.create("w")).set(Maps.of("late", true)));
		pending.updateData(pending.getData().assoc(Fields.OUTPUT, Strings.create("late-output")));
		assertTrue(fresh.jobs().getAll().isEmpty());
		assertFalse(fresh.cursor().path(Strings.create("w")).exists());
	}

	@Test void automaticAdmissionCanRecreateAnEmptyAccount() throws Exception {
		Engine open = TestEngine.ENGINE;
		AString did = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		User old = open.admitUser(did);
		TestAgents.ensure(old, "removed", Maps.empty(), null);
		open.deleteUser(open.venueContext(), did);
		User fresh = open.admitUser(did);
		assertTrue(fresh.agents().all().isEmpty());
		assertThrows(AuthException.class, old::get);
	}

	@Test void claimedScheduledWorkCannotRunInARecreatedAccount() throws Exception {
		for (boolean tracked : new boolean[] {true, false}) {
			// Deleted before or after the recorded fire's live Job was published
			for (boolean accepted : new boolean[] {true, false}) {
				AString did = user();
				RequestContext ctx = RequestContext.of(did);
				AMap<AString, ACell> input = Maps.of(Fields.PATH, "w/stale-schedule", Fields.VALUE, "old-input");
				JobManager.Prepared prepared = tracked
					? engine.jobs().prepareTracked(Strings.create("v/ops/covia/write"), input, ctx)
					: engine.jobs().prepareUntracked(Strings.create("v/ops/covia/write"), input, ctx);
				engine.getVenueState().execute(prepared::record);
				if (accepted) prepared.accept();
				delete(did);
				engine.getVenueState().users().create(did);
				if (accepted) assertEquals(Status.CANCELLED, prepared.start().getStatus());
				else assertThrows(AuthException.class, prepared::start);
				assertFalse(engine.getVenueState().users().user(did).cursor().path(Strings.create("w")).exists());
			}
		}
	}

	@Test void aJobIsNotRecordedForADeletedAccountButItsTransactionStillCommits() throws Exception {
		// What a scheduled fire's claim relies on: the claim commits without the Job
		AString did = user();
		AString other = user();
		JobManager.Prepared prepared = engine.jobs().prepareTracked(Strings.create("v/ops/covia/write"),
			Maps.of(Fields.PATH, "w/never", Fields.VALUE, "never"), RequestContext.of(did));
		delete(did);
		AuthException refused = engine.getVenueState().transact(draft -> {
			AuthException failure = null;
			try {
				prepared.record(draft);
			} catch (AuthException e) {
				failure = e;
			}
			Covia.entry(draft.users().user(other).cursor(), Strings.create("w")).set(Maps.of("claimed", true));
			return failure;
		});
		prepared.abandon();
		assertNotNull(refused);
		assertNotNull(engine.getVenueState().users().user(other).cursor().path(Strings.create("w")).getOrDefault(null));
		assertNull(engine.getVenueState().users().user(did), "Recording must not bring the account back");
		assertThrows(IllegalStateException.class, prepared::start, "Nothing was recorded to start");
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
			assertTrue(engine.getVenueState().users().user(did).agents().all().isEmpty());
			assertTrue(engine.getVenueState().users().user(did).jobs().getAll().isEmpty());
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
		User old = engine.getVenueState().users().user(did);
		TestAgents.ensure(old, "private-agent", Maps.empty(), null);
		delete(did);
		assertNull(engine.getAuth().getUser(id));
		assertFalse(engine.getAuth().isAuthenticationKeyActive(id, key));
		String directory = engine.getAuth().getUsers().toString();
		for (String pii : new String[] {"erase-profile@example.test", "Private Name", "private-provider-sub", "private-label"}) {
			assertFalse(directory.contains(pii));
		}
		ACell replacement = engine.getAuth().provisionLogin(id, profile);
		assertEquals(did, RT.getIn(replacement, Fields.DID));
		assertTrue(engine.getVenueState().users().user(did).agents().all().isEmpty());
		assertFalse(engine.getAuth().isAuthenticationKeyActive(id, key));
		assertThrows(AuthException.class, old::get);
	}
}

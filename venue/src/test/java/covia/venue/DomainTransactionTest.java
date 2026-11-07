package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.SignedData;
import convex.core.data.prim.CVMLong;
import convex.lattice.cursor.Cursors;
import covia.exception.AuthException;
import covia.exception.ConflictException;
import covia.exception.InvalidInputException;
import covia.api.Fields;
import covia.lattice.Covia;
import covia.lattice.CoviaPath;

/** Domain transactions use the same child APIs, with one local commit boundary. */
class DomainTransactionTest {

	private static final AString DID = Strings.create("did:key:transaction-user");
	/** The stretch of a {@code w/} path inside the user's workspace. */
	private static CoviaPath path(String path) { return CoviaPath.parse(path).rest(); }

	@Test
	void jobHistoryComposesWithWorkspaceAndRetainedDraftsStayIsolated() {
		var venue = VenueState.create(AKeyPair.generate());
		var user = venue.users().ensure(DID);
		var id = Blob.fromHex("01");
		assertNotNull(user.jobs().create(id, Maps.of(Fields.ID, id, Fields.STATUS, covia.grid.Status.STARTED)));
		assertNull(user.jobs().create(id, Maps.of(Fields.STATUS, covia.grid.Status.FAILED)));
		AMap<AString, ACell> message = Maps.of(Fields.MESSAGE_ID, "one");
		var retained = new AtomicReference<JobState>();
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> user.transact(draft -> {
			retained.set(draft.jobs().get(id));
			assertTrue(draft.jobs().get(id).appendHistory(message));
			draft.workspace().write(path("w/received"), CVMLong.ONE);
			throw new IllegalStateException();
		}));
		assertEquals(before, venue.get());
		retained.get().appendHistory(Maps.of(Fields.MESSAGE_ID, "late"));
		assertEquals(before, venue.get());
		user.transact(draft -> {
			assertTrue(draft.jobs().get(id).appendHistory(message));
			draft.workspace().write(path("w/received"), CVMLong.ONE);
		});
		assertEquals(convex.core.data.Vectors.of(message), user.jobs().getRecord(id).get(Fields.HISTORY));
		assertEquals(CVMLong.ONE, user.workspace().read(path("w/received")));
		assertFalse(user.jobs().get(id).appendHistory(message));
	}

	@Test
	void jobTransactionRetriesAgainstDeletionWithoutRecreatingTheRow() throws Exception {
		var user = VenueState.create(AKeyPair.generate()).users().ensure(DID);
		var id = Blob.fromHex("01");
		user.jobs().create(id, Maps.of(Fields.ID, id, Fields.STATUS, covia.grid.Status.STARTED));
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var appended = new AtomicBoolean();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> user.jobs().get(id).transact(draft -> {
				appended.set(draft.appendHistory(Maps.of(Fields.MESSAGE_ID, "raced")));
				entered.countDown();
				await(resume);
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				assertTrue(user.jobs().remove(id));
			} finally { resume.countDown(); }
			pending.get(10, TimeUnit.SECONDS);
		}
		assertFalse(appended.get());
		assertNull(user.jobs().get(id));
		assertTrue(user.jobs().getAll().isEmpty());
	}

	@Test
	void sessionChangesComposeWithAgentChangesAndRollback() {
		var venue = VenueState.create(AKeyPair.generate());
		var agent = venue.users().ensure(DID).ensureAgent("sessions", Maps.empty(), null);
		var sid = Blob.fromHex("01");
		agent.ensureSession(sid, DID);
		var retained = new AtomicReference<SessionState>();
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> agent.transact(draft -> {
			retained.set(draft.session(sid));
			draft.setStatus(AgentState.RUNNING);
			draft.session(sid).transact(session -> {
				assertTrue(session.setTitle(Strings.create("draft")));
				assertTrue(session.beginCycle(CVMLong.ONE, null, 0));
			});
			assertEquals(before, venue.get());
			throw new IllegalStateException();
		}));
		assertEquals(before, venue.get());
		retained.get().setTitle(Strings.create("late"));
		assertEquals(before, venue.get());
		agent.transact(draft -> {
			draft.setStatus(AgentState.RUNNING);
			draft.session(sid).beginCycle(CVMLong.ONE, null, 0);
		});
		assertEquals(AgentState.RUNNING, agent.getStatus());
		assertEquals(CVMLong.ONE, agent.session(sid).cycleEpoch());
	}

	@Test
	void summaryPublicationRetriesAgentAdmissionAfterConcurrentStop() throws Exception {
		var agent = VenueState.create(AKeyPair.generate()).users().ensure(DID)
			.ensureAgent("sessions", Maps.empty(), null);
		var sid = Blob.fromHex("01");
		var snapshot = agent.ensureSession(sid, DID);
		var instruction = Strings.create("summarise");
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var published = new AtomicReference<AMap<AString, ACell>>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> agent.transact(draft -> {
				published.set(draft.saveSessionSummary(sid, snapshot, instruction, Maps.of("result", "summary")));
				entered.countDown();
				await(resume);
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				agent.setStatus(AgentState.SUSPENDED);
			} finally { resume.countDown(); }
			pending.get(10, TimeUnit.SECONDS);
		}
		assertNull(published.get(), "the rejected retry must replace the first attempt's result");
		assertNull(SessionState.sessionSummary(agent.getSession(sid), instruction));
		assertEquals(AgentState.SUSPENDED, agent.getStatus());
	}

	@Test
	void configChangeRunsAgainstTheConfigItReplaces() throws Exception {
		var caps = Strings.create("caps");
		var model = Strings.create("model");
		var agent = VenueState.create(AKeyPair.generate()).users().ensure(DID)
			.ensureAgent("config", Maps.of(caps, "wide", model, "a"), null);
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var seen = new CopyOnWriteArrayList<ACell>();
		AMap<AString, ACell> committed;
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> agent.updateConfigAndState(config -> {
				seen.add(config.get(caps));
				entered.countDown();
				await(resume);
				return config.assoc(model, Strings.create("b"));
			}, null));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				agent.updateConfigAndState(config -> config.assoc(caps, Strings.create("narrow")), null);
			} finally { resume.countDown(); }
			committed = pending.get(10, TimeUnit.SECONDS);
		}
		assertEquals(List.of(Strings.create("wide"), Strings.create("narrow")), seen,
			"a change that lost the race runs again against the config that won");
		assertEquals(Strings.create("narrow"), committed.get(caps), "the concurrent change stands");
		assertEquals(Strings.create("b"), committed.get(model));
		assertEquals(committed, agent.getConfig());
	}

	@Test
	void sessionTransactionDoesNotResurrectConcurrentDeletion() throws Exception {
		var agent = VenueState.create(AKeyPair.generate()).users().ensure(DID)
			.ensureAgent("sessions", Maps.empty(), null);
		var sid = Blob.fromHex("01");
		agent.ensureSession(sid, DID);
		var session = agent.session(sid);
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var accepted = new AtomicBoolean();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> session.transact(draft -> {
				accepted.set(draft.setTitle(Strings.create("pending")));
				entered.countDown();
				await(resume);
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				agent.removeSession(sid);
			} finally { resume.countDown(); }
			pending.get(10, TimeUnit.SECONDS);
		}
		assertFalse(accepted.get());
		assertNull(agent.getSession(sid));
		assertFalse(session.beginCycle(CVMLong.ONE, null, 0));
	}

	@Test
	void sessionUpdateReportsDeletionOnRetryAndPreservesCycleFence() throws Exception {
		var agent = VenueState.create(AKeyPair.generate()).users().ensure(DID)
			.ensureAgent("sessions", Maps.empty(), null);
		var sid = Blob.fromHex("01");
		agent.ensureSession(sid, DID);
		var session = agent.session(sid);
		session.beginCycle(CVMLong.ONE, null, 0);
		assertFalse(session.updateFrames(CVMLong.create(2), frames -> frames));
		assertFalse(session.updateQuiescentFrames(frames -> frames));
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> session.updateFrames(CVMLong.ONE, frames -> {
				entered.countDown();
				await(resume);
				return frames.assoc(0, ((AMap<AString, ACell>) frames.get(0))
					.assoc(Strings.create("description"), Strings.create("changed")));
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				agent.removeSession(sid);
			} finally { resume.countDown(); }
			assertFalse(pending.get(10, TimeUnit.SECONDS));
		}
		assertNull(agent.getSession(sid));
	}

	@Test
	void authAndScheduleChildrenRemainOnTheVenueDraft() {
		var venue = VenueState.create(AKeyPair.generate());
		var id = Strings.create("alice");
		ACell before = venue.get();
		var authDraft = new AtomicReference<AuthState>();
		var scheduleDraft = new AtomicReference<ScheduleState>();
		assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			authDraft.set(draft.auth());
			scheduleDraft.set(draft.schedule());
			draft.auth().ensureManagedUser(id, DID);
			draft.schedule().schedule(Strings.create("v/test/ops/echo"), null, DID, DID,
				null, 1000, null, false, null, 100);
			assertNull(venue.auth().getUser(id));
			assertTrue(venue.schedule().events().isEmpty());
			throw new IllegalStateException();
		}));
		assertEquals(before, venue.get());
		authDraft.get().ensureManagedUser(Strings.create("late"), DID);
		scheduleDraft.get().cancelUser(DID);
		assertEquals(before, venue.get(), "retained children must not mutate the live venue");
		venue.transact(draft -> {
			draft.auth().ensureManagedUser(id, DID);
			draft.schedule().schedule(Strings.create("v/test/ops/echo"), null, DID, DID,
				null, 1000, null, false, null, 100);
		});
		assertNotNull(venue.auth().getUser(id));
		assertEquals(1, venue.schedule().events().count());
	}

	@Test
	void concurrentAuthDraftCannotBindTheSameKeyToTwoUsers() throws Exception {
		var auth = VenueState.create(AKeyPair.generate()).auth();
		var alice = Strings.create("alice");
		var bob = Strings.create("bob");
		auth.ensureManagedUser(alice, DID);
		auth.ensureManagedUser(bob, Strings.create("did:key:other-user"));
		var key = convex.auth.ucan.UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> auth.transact(draft -> {
				draft.addAuthenticationKey(alice, key, DID, null);
				entered.countDown();
				await(resume);
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				assertTrue(auth.addAuthenticationKey(bob, key, DID, null));
			} finally { resume.countDown(); }
			var failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> pending.get(10, TimeUnit.SECONDS));
			assertInstanceOf(ConflictException.class, failure.getCause());
			assertEquals(AuthState.KEY_ALREADY_BOUND + bob, failure.getCause().getMessage());
		}
		assertFalse(auth.isAuthenticationKeyActive(alice, key));
		assertTrue(auth.isAuthenticationKeyActive(bob, key));
	}

	@Test
	void agentRecordHasOneStampPolicyIncludingReplacementAndNoOps() {
		var clockReads = new AtomicInteger();
		var ticks = new AtomicLong(100);
		var context = VenueContext.create(() -> {
			clockReads.incrementAndGet();
			return CVMLong.create(ticks.get());
		});
		var agent = new AgentState(context, Cursors.root(null), null, Strings.create("assistant"));
		agent.initialise(Maps.empty(), null);
		assertEquals(1, clockReads.getAndSet(0));
		ticks.set(200);
		agent.setStatus(AgentState.SUSPENDED);
		assertEquals(1, clockReads.getAndSet(0));
		assertEquals(CVMLong.create(200), agent.getRecord().get(AgentState.K_TURN_TS));
		var before = agent.getRecord();
		ticks.set(300);
		agent.setStatus(AgentState.SUSPENDED);
		assertEquals(before, agent.getRecord(), "no-op must not change the version");
		ticks.set(50);
		agent.putRecord(Maps.of(AgentState.KEY_STATUS, AgentState.SLEEPING));
		assertEquals(CVMLong.create(200), agent.getRecord().get(AgentState.K_TURN_TS),
			"replacement must preserve the previous version floor when the clock regresses");
	}

	@Test
	void wakeIntentEventAndHandleShareTheVenueTransaction() {
		var venue = VenueState.create(AKeyPair.generate());
		var agentId = Strings.create("assistant");
		var agent = venue.users().ensure(DID).ensureAgent(agentId, Maps.empty(), null);
		var sid = Blob.fromHex("aa");
		agent.ensureSession(sid, DID);
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			draft.setAgentThreadWakeTime(DID, agentId, AgentState.ThreadKind.SESSION, sid, 1000);
			assertEquals(before, venue.get());
			assertNotNull(draft.users().get(DID).agent(agentId).wakeHandle());
			throw new IllegalStateException();
		}));
		assertEquals(before, venue.get(), "aborted wake must leave no event or handle");
		venue.setAgentThreadWakeTime(DID, agentId, AgentState.ThreadKind.SESSION, sid, 1000);
		var events = (Index<?, ?>) venue.schedule().cursor().path(ScheduleState.K_EVENTS).get();
		assertEquals(1, events.count());
		assertEquals(CVMLong.create(1000), agent.getSession(sid).get(Fields.WAKE_TIME));
		assertNotNull(events.get(agent.wakeHandle()));
		venue.setAgentThreadWakeTime(DID, agentId, AgentState.ThreadKind.SESSION, sid, 0);
		assertNull(agent.wakeHandle());
		assertNull(agent.getSession(sid).get(Fields.WAKE_TIME));
		assertTrue(((Index<?, ?>) venue.schedule().cursor().path(ScheduleState.K_EVENTS).get()).isEmpty());
	}

	@Test
	void concurrentWakeIntentRetriesWithoutOrphanEvents() throws Exception {
		var venue = VenueState.create(AKeyPair.generate());
		var agentId = Strings.create("assistant");
		var agent = venue.users().ensure(DID).ensureAgent(agentId, Maps.empty(), null);
		var sid = Blob.fromHex("aa");
		var other = Blob.fromHex("bb");
		agent.ensureSession(sid, DID);
		agent.ensureSession(other, DID);
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var attempts = new AtomicInteger();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> venue.transact(draft -> {
				draft.setAgentThreadWakeTime(DID, agentId, AgentState.ThreadKind.SESSION, sid, 2000);
				if (attempts.incrementAndGet() == 1) { entered.countDown(); await(resume); }
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				venue.setAgentThreadWakeTime(DID, agentId, AgentState.ThreadKind.SESSION, other, 1000);
			} finally { resume.countDown(); }
			pending.get(10, TimeUnit.SECONDS);
		}
		assertEquals(2, attempts.get());
		assertEquals(CVMLong.create(2000), agent.getSession(sid).get(Fields.WAKE_TIME));
		assertEquals(CVMLong.create(1000), agent.getSession(other).get(Fields.WAKE_TIME));
		var events = (Index<?, ?>) venue.schedule().cursor().path(ScheduleState.K_EVENTS).get();
		assertEquals(1, events.count());
		assertEquals(CVMLong.create(1000), convex.core.lang.RT.getIn(events.get(agent.wakeHandle()), ScheduleState.K_TIME));
	}

	@Test
	void provisioningIsAtomicAndSignsOnlyOnce() {
		var key = AKeyPair.generate();
		var signatures = new AtomicInteger();
		var fail = new AtomicBoolean();
		java.util.function.Function<ACell, SignedData<ACell>> signer = value -> {
			signatures.incrementAndGet();
			if (fail.get()) throw new IllegalStateException("test signer unavailable");
			return key.signData(value);
		};
		var venue = VenueState.fromRoot(Cursors.root(Index.none()), key.getAccountKey(), signer, VenueContext.SYSTEM);
		var id = Strings.create("alice");
		convex.core.data.AMap<AString, ACell> profile = Maps.of(Fields.NAME, Strings.create("Alice"));
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			draft.provisionLogin(id, DID, profile);
			assertNull(venue.users().get(DID));
			throw new IllegalStateException();
		}));
		assertEquals(before, venue.get());
		assertEquals(0, signatures.get());
		fail.set(true);
		assertThrows(IllegalStateException.class, () -> venue.provisionLogin(id, DID, profile));
		assertEquals(before, venue.get(), "failed signing cannot leave an orphan account");
		fail.set(false);
		signatures.set(0);
		var result = venue.provisionLogin(id, DID, profile);
		assertEquals(1, signatures.get());
		assertNotNull(venue.users().get(DID));
		assertEquals(result, convex.core.lang.RT.getIn(venue.get(), Covia.USERS, id));
		assertEquals(DID, result.get(Fields.DID));
	}

	@Test
	void compoundVenueChangeIsPrivateAndRollsBackOnFailure() {
		var key = AKeyPair.generate();
		var venue = VenueState.create(key);
		var keys = SecretStore.keys(key);
		ACell before = venue.get();
		var failure = new IllegalStateException("abort test transaction");
		assertSame(failure, assertThrows(IllegalStateException.class, () -> venue.transact(draft -> {
			var user = draft.users().ensure(DID);
			user.ensureAgent("assistant", Maps.empty(), null);
			user.secrets().store("token", "private", keys);
			user.workspace().write(path("w/ready"), CVMLong.ONE);
			draft.assets().store(Strings.create("{\"name\":\"private\"}"), null);
			assertNull(venue.users().get(DID));
			assertEquals(0, venue.assets().count());
			throw failure;
		})));
		assertEquals(before, venue.get(), "an aborted draft must not even change metadata");

		venue.transact(draft -> {
			var user = draft.users().ensure(DID);
			user.ensureAgent("assistant", Maps.empty(), null);
			user.secrets().store("token", "committed", keys);
			user.workspace().write(path("w/ready"), CVMLong.ONE);
		});
		var user = venue.users().get(DID);
		assertNotNull(user.agent("assistant"));
		assertEquals(Strings.create("committed"), user.secrets().decrypt("token", keys));
		assertEquals(CVMLong.ONE, user.workspace().read(path("w/ready")));
	}

	@Test
	void nestedTransactionsAndRetainedChildrenStayOnTheirDraft() {
		var key = AKeyPair.generate();
		var user = VenueState.create(key).users().ensure(DID);
		var keys = SecretStore.keys(key);
		var retained = new AtomicReference<User>();
		var agentDraft = new AtomicReference<AgentState>();
		user.transact(draft -> {
			retained.set(draft);
			draft.ensureAgent("assistant", Maps.empty(), null).transact(agent -> {
				agentDraft.set(agent);
				agent.setStatus(Strings.create("PAUSED"));
				assertNull(user.agent("assistant"));
			});
			draft.secrets().transact(secrets -> secrets.store("token", "saved", keys));
			draft.workspace().transact(workspace -> workspace.write(path("w/ready"), CVMLong.ONE));
			assertNull(user.secrets().decrypt("token", keys));
		});
		ACell committed = user.cursor().get();
		assertEquals(Strings.create("PAUSED"), user.agent("assistant").getStatus());
		assertEquals(Strings.create("saved"), user.secrets().decrypt("token", keys));
		assertEquals(CVMLong.ONE, user.workspace().read(path("w/ready")));
		retained.get().secrets().store("token", "too late", keys);
		agentDraft.get().setStatus(Strings.create("COMPLETE"));
		retained.get().workspace().write(path("w/late"), CVMLong.ONE);
		assertEquals(committed, user.cursor().get());
	}

	@Test
	void concurrentVenueEditRetriesInsteadOfSelectingOneWholeVersion() throws Exception {
		var venue = VenueState.create(AKeyPair.generate());
		var user = venue.users().ensure(DID);
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		var attempts = new AtomicInteger();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> venue.transact(draft -> {
				draft.users().get(DID).workspace().write(path("w/transaction"), CVMLong.ONE);
				if (attempts.incrementAndGet() == 1) {
					entered.countDown();
					await(resume);
				}
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				user.workspace().write(path("w/concurrent"), CVMLong.ONE);
			} finally { resume.countDown(); }
			pending.get(10, TimeUnit.SECONDS);
		}
		assertEquals(2, attempts.get());
		assertEquals(CVMLong.ONE, user.workspace().read(path("w/concurrent")));
		assertEquals(CVMLong.ONE, user.workspace().read(path("w/transaction")));
	}

	@Test
	void transactionCannotCommitIntoARecreatedAccount() throws Exception {
		var venue = VenueState.create(AKeyPair.generate());
		var user = venue.users().ensure(DID);
		var entered = new CountDownLatch(1);
		var resume = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var pending = executor.submit(() -> user.transact(draft -> {
				draft.workspace().write(path("w/stale"), CVMLong.ONE);
				entered.countDown();
				await(resume);
			}));
			try {
				assertTrue(entered.await(10, TimeUnit.SECONDS));
				venue.deleteUser(DID, DID);
				venue.users().finishDeletion(DID);
				assertTrue(venue.users().create(DID));
			} finally { resume.countDown(); }
			var failure = assertThrows(java.util.concurrent.ExecutionException.class,
				() -> pending.get(10, TimeUnit.SECONDS));
			assertInstanceOf(AuthException.class, failure.getCause());
			assertEquals(Users.ACCOUNT_DELETED, failure.getCause().getMessage());
		}
		assertNull(venue.users().get(DID).workspace().read(path("w/stale")));
	}

	@Test
	void commitDoesNotApplyTheComponentsTimestampPolicyTwice() {
		var key = AKeyPair.generate();
		var ticks = new AtomicLong(100);
		var context = VenueContext.create(() -> CVMLong.create(ticks.incrementAndGet()));
		var venue = VenueState.fromRoot(Cursors.root(Index.none()), key.getAccountKey(), key::signData, context);
		var staged = new AtomicReference<ACell>();
		venue.transact(draft -> {
			draft.users().ensure(DID).ensureAgent("assistant", Maps.empty(), null);
			staged.set(draft.get());
		});
		assertEquals(staged.get(), venue.get());
		var user = venue.users().get(DID);
		user.transact(draft -> {
			draft.workspace().write(path("w/value"), CVMLong.ONE);
			staged.set(draft.cursor().get());
		});
		assertEquals(staged.get(), user.cursor().get());
		var agent = user.agent("assistant");
		agent.transact(draft -> {
			draft.setStatus(Strings.create("PAUSED"));
			staged.set(draft.getRecord());
		});
		assertEquals(staged.get(), agent.getRecord());
	}

	@Test
	void venueTransactionSignsOnlyTheCompletedBatchAndSigningFailureRollsBack() {
		var key = AKeyPair.generate();
		var signatures = new AtomicInteger();
		var fail = new AtomicBoolean();
		java.util.function.Function<ACell, SignedData<ACell>> signer = value -> {
			signatures.incrementAndGet();
			if (fail.get()) throw new IllegalStateException("test signer unavailable");
			return key.signData(value);
		};
		var venue = VenueState.fromRoot(Cursors.root(Index.none()), key.getAccountKey(), signer, VenueContext.SYSTEM);
		venue.transact(draft -> {
			var user = draft.users().ensure(DID);
			user.workspace().write(path("w/a"), CVMLong.ONE);
			user.workspace().write(path("w/b"), CVMLong.ONE);
			assertEquals(0, signatures.get());
		});
		assertEquals(1, signatures.get());
		venue.transact(draft -> assertNotNull(draft.users().get(DID)));
		assertEquals(1, signatures.get(), "unchanged transactions do not sign");
		ACell before = venue.get();
		fail.set(true);
		assertThrows(IllegalStateException.class, () -> venue.transact(draft ->
			draft.users().get(DID).workspace().write(path("w/c"), CVMLong.ONE)));
		assertEquals(before, venue.get());
		fail.set(false);
		venue.transact(draft -> draft.users().get(DID).workspace().write(path("w/c"), CVMLong.ONE));
		assertEquals(CVMLong.ONE, venue.users().get(DID).workspace().read(path("w/c")));
	}

	@Test
	void narrowStoreTransactionsAlsoDiscardFailedBatches() {
		var key = AKeyPair.generate();
		var venue = VenueState.create(key);
		var users = venue.users();
		assertThrows(IllegalStateException.class, () -> users.transact(draft -> {
			draft.ensure(DID);
			throw new IllegalStateException();
		}));
		assertNull(users.get(DID));
		var user = users.ensure(DID);
		var keys = SecretStore.keys(key);
		ACell before = venue.get();
		assertThrows(IllegalStateException.class, () -> user.secrets().transact(draft -> {
			draft.store("token", "discarded", keys);
			throw new IllegalStateException();
		}));
		assertThrows(IllegalStateException.class, () -> user.assets().transact(draft -> {
			draft.store(Strings.create("{\"name\":\"discarded\"}"), null);
			throw new IllegalStateException();
		}));
		assertThrows(InvalidInputException.class, () -> user.workspace().transact(draft -> {
			draft.write(path("w/first"), CVMLong.ONE);
			draft.write(path("w/first/child"), CVMLong.ONE); // shape conflict aborts both writes
		}));
		assertEquals(before, venue.get());
	}

	private static void await(CountDownLatch latch) {
		try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}
}

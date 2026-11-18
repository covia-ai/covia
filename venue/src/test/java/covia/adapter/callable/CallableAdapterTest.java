package covia.adapter.callable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.token.AccessToken;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.NotFoundException;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Caps;
import covia.venue.Engine;
import covia.venue.JobManager;
import covia.venue.RequestContext;
import covia.venue.Scheduler;
import covia.venue.TestEngine;

/**
 * Callables (CALLABLES.md): a call runs its callable's operation with the
 * callable's authority, in its owner's namespace, with the invoker as caller;
 * only holders of {@code callable/call} or callers its gate admits may call
 * it; and a call never holds more than its caps, nor anything once the
 * callable is gone. Each refusal has a control that differs from it in one
 * thing and succeeds.
 */
public class CallableAdapterTest {

	private final Engine engine = TestEngine.ENGINE;

	private static final AVector<ACell> GUESTBOOK_CAPS = Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE));

	private Job run(String op, ACell input, RequestContext ctx) {
		Job job = engine.jobs().invokeOperation(op, input, ctx);
		TestEngine.awaitJob(job, Job::isFinished, 10000, () -> op + " never finished");
		return job;
	}

	private static ACell complete(Job job) {
		assertEquals(Status.COMPLETE, job.getStatus(), job::getErrorMessage);
		return job.getOutput();
	}

	private static String failure(Job job) {
		assertEquals(Status.FAILED, job.getStatus(), () -> "expected a refusal, got " + job.getData());
		return job.getErrorMessage();
	}

	private static ACell cap(String with, AString can) {
		return Capability.create(Strings.create(with), can);
	}

	/** A user with keys of its own, so it can sign grants. */
	private record Keyed(AKeyPair keys, AString did) {
		static Keyed generate() {
			AKeyPair keys = AKeyPair.generate();
			return new Keyed(keys, UCAN.toDIDKey(keys.getAccountKey()));
		}
	}

	/** Creates agent {@code id} of {@code owner} with {@code caps}, and returns the context it acts in. */
	private RequestContext agent(AString owner, String id, ACell caps) {
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, id,
			Fields.CONFIG, Maps.of(Fields.OPERATION, "v/test/ops/echo", Fields.CAPS, caps)), RequestContext.of(owner)));
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	private Job create(RequestContext ctx, String name, String operation, ACell caps, String gate) {
		AMap<AString, ACell> input = Maps.of(Fields.NAME, name, Fields.OPERATION, operation, Fields.CAPS, caps);
		return run("v/ops/callable/create", (gate == null) ? input : input.assoc(Fields.GATE, Strings.create(gate)), ctx);
	}

	private Job call(RequestContext ctx, String callable, ACell input) {
		return run("v/ops/callable/call", Maps.of(Fields.CALLABLE, callable, Fields.INPUT, input), ctx);
	}

	/** {@code owner}'s guestbook, empty: a list its callable appends to, the parent of which the callable may not create. */
	private void openGuestbook(AString owner) {
		complete(run("v/ops/covia/write", Maps.of(Fields.PATH, "w/guestbook", Fields.VALUE, Vectors.empty()), RequestContext.of(owner)));
	}

	private static AMap<AString, ACell> append(String path, String value) {
		return Maps.of(Fields.PATH, path, Fields.VALUE, value);
	}

	/** What {@code owner} holds at {@code path}, as it reads it. */
	private ACell held(AString owner, String path) {
		return RT.getIn(complete(run("v/ops/covia/read", Maps.of(Fields.PATH, path), RequestContext.of(owner))), Fields.VALUE);
	}

	private boolean mayWrite(RequestContext ctx, AString owner, String path) {
		return engine.authorityCovers(ctx, Strings.create(owner + "/" + path), Capability.CRUD_WRITE);
	}

	/** The reference of {@code owner}'s callable {@code name}, as a call and the work it leaves name it. */
	private AMap<AString, ACell> reference(AString owner, String name) {
		return engine.resolveCallable(Callable.reference(owner, Strings.create(name),
			callables().callable(owner, Strings.create(name)).id())).reference();
	}

	private Callables callables() {
		return ((CallableAdapter) engine.getAdapter(CallableAdapter.NAME)).callables();
	}

	@Test
	public void aCallablesCapsAreAFieldOfItsRecord() {
		Callables callables = new Callables(convex.lattice.cursor.Cursors.create(Maps.empty()));
		AString owner = Strings.create("did:test:caps-owner");
		AString name = Strings.create("fn");
		assertTrue(callables.create(owner, name, Maps.of(Fields.ISSUER, owner)));
		Caps caps = callables.callable(owner, name).caps();
		assertTrue(caps.isUnrestricted(), "no caps field is unrestricted");
		caps.clear();
		assertFalse(callables.callable(owner, name).caps().isUnrestricted(), "a change creates the field");
		AString missing = Strings.create("missing");
		assertThrows(convex.lattice.PathWriteException.class, () -> callables.callable(owner, missing).caps().clear());
		assertFalse(callables.list(owner).containsKey(missing), "no record is created for a missing callable");
	}

	@Test
	public void aCallActsWithTheCallablesAuthorityInItsOwnersNamespace(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		openGuestbook(alice);
		complete(create(RequestContext.of(alice), "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, "v/ops/callable/gates/anyone"));
		String guestbook = alice + "/x/callable/guestbook";

		// Bob may not write Alice's guestbook, but the callable may, for him
		failure(run("v/ops/covia/append", append(alice + "/w/guestbook", "direct"), RequestContext.of(bob)));
		Job called = call(RequestContext.of(bob), guestbook, append("w/guestbook", "Hello from Bob"));
		complete(called);
		assertEquals(Vectors.of("Hello from Bob"), held(alice, "w/guestbook"), "the path names Alice's guestbook");
		assertNull(held(bob, "w/guestbook"), "not the caller's");
		assertEquals(bob, called.getCaller(), "the call is the invoker's");

		// The caps bound it, wherever the path points
		failure(call(RequestContext.of(bob), guestbook, append("w/private", "x")));
		failure(call(RequestContext.of(bob), guestbook, append(bob + "/w/guestbook", "x")));
		assertNull(held(alice, "w/private"));
	}

	@Test
	public void onlyHoldersOfCallableCallOrCallersItsGateAdmitsMayCall(TestInfo info) {
		Keyed alice = Keyed.generate();
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice.did());
		openGuestbook(alice.did());
		complete(create(owner, "private", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		String callable = alice.did() + "/x/callable/private";
		String missing = alice.did() + "/x/callable/missing";

		// Without a gate, a caller without callable/call is told what a missing callable tells it
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(callable), failure(call(RequestContext.of(bob), callable, append("w/guestbook", "x"))));
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(missing), failure(call(RequestContext.of(bob), missing, append("w/guestbook", "x"))));
		complete(call(owner, "private", append("w/guestbook", "from Alice")));

		// The owner's grant of callable/call lets Bob call it
		Keyed granted = Keyed.generate();
		UCAN grant = UCAN.create(alice.keys(), UCAN.fromDIDKey(granted.did()), engine.currentTimestamp().longValue() / 1000 + 3600,
			Vectors.of(cap(callable, Abilities.CALLABLE_CALL)), Vectors.empty());
		complete(call(RequestContext.of(granted.did()).withProofs(Vectors.of(grant.toMap())), callable, append("w/guestbook", "granted")));

		// A gate the provider publishes admits whom it lists, and a listed user's agents
		complete(run("v/ops/covia/write", Maps.of(Fields.PATH, "o/team-gate", Fields.VALUE, Maps.of(
			Fields.NAME, "Team gate",
			Fields.OPERATION, Maps.of(Fields.ADAPTER, "callable:gates/callers",
				Fields.DEFAULT, Maps.of(Fields.CALLERS, Vectors.of(bob))))), owner));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "private", Fields.GATE, "o/team-gate"), owner));
		complete(call(RequestContext.of(bob), callable, append("w/guestbook", "from Bob")));
		complete(call(agent(bob, "helper", Vectors.of(cap("v/ops/", Abilities.INVOKE), cap(callable, Abilities.CALLABLE_CALL))),
			callable, append("w/guestbook", "from Bob's agent")));
		complete(call(engine.harnessContext(bob, Strings.create("helper")), callable, append("w/guestbook", "from its harness")));
		String refused = failure(call(RequestContext.of(carol), callable, append("w/guestbook", "from Carol")));
		assertTrue(refused.contains(CallableAdapter.NOT_A_CALLER.formatted(carol)), refused);
		assertEquals(Vectors.of("from Alice", "granted", "from Bob", "from Bob's agent", "from its harness"), held(alice.did(), "w/guestbook"));

		// A gate that refuses everyone admits no one, and removing the gate makes it private again
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "private", Fields.GATE, "v/test/ops/denygate"), owner));
		failure(call(RequestContext.of(bob), callable, append("w/guestbook", "x")));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "private", Fields.GATE, null), owner));
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(callable), failure(call(RequestContext.of(bob), callable, append("w/guestbook", "x"))));
	}

	/** {@code owner}'s agent {@code id} under {@code controller}, which may be in another account, with {@code caps}. */
	private RequestContext controlled(AString owner, String id, AString controller, AVector<ACell> caps) {
		engine.getVenueState().users().ensure(controller);
		engine.getVenueState().users().ensure(owner).agents().create(Strings.create(id), Maps.of(covia.venue.Config.CAPS, caps),
			null, null, controller);
		return engine.context(owner, Principals.agentDID(owner, Strings.create(id)));
	}

	@Test
	public void aGateAdmitsWhatActsForWhomItListsWithinItsCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		AString dan = TestEngine.uniqueDID("dan-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		complete(run("v/ops/covia/write", Maps.of(Fields.PATH, "o/team-gate", Fields.VALUE, Maps.of(
			Fields.NAME, "Team gate",
			Fields.OPERATION, Maps.of(Fields.ADAPTER, "callable:gates/callers",
				Fields.DEFAULT, Maps.of(Fields.CALLERS, Vectors.of(bob))))), owner));
		complete(create(owner, "team", "v/ops/covia/append", GUESTBOOK_CAPS, "o/team-gate"));
		complete(create(owner, "open", "v/ops/covia/append", GUESTBOOK_CAPS, "v/ops/callable/gates/anyone"));
		String team = alice + "/x/callable/team";
		String open = alice + "/x/callable/open";
		ACell invoke = cap("v/ops/", Abilities.INVOKE);

		// Listing Bob admits his agents, each as far as its caps cover calling
		complete(call(controlled(bob, "free", bob, null), team, append("w/guestbook", "free")));
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(team),
			failure(call(controlled(bob, "narrow", bob, Vectors.of(invoke)), team, append("w/guestbook", "narrow"))));
		complete(call(controlled(bob, "member", bob, Vectors.of(invoke, cap(team, Abilities.CALLABLE_CALL))), team, append("w/guestbook", "member")));

		// It admits an agent elsewhere that Bob controls, never one in Bob's account that Carol controls
		complete(call(controlled(dan, "lent", bob, Vectors.of(invoke, cap(team, Abilities.CALLABLE_CALL))), team, append("w/guestbook", "lent")));
		String refused = failure(call(controlled(bob, "carols", carol, Vectors.of(invoke, cap(team, Abilities.CALLABLE_CALL))), team,
			append("w/guestbook", "carols")));
		assertTrue(refused.contains(CallableAdapter.NOT_A_CALLER.formatted(Principals.agentDID(bob, Strings.create("carols")))), refused);

		// A gate that admits anyone admits a capped agent only as far as its caps
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(open),
			failure(call(controlled(bob, "narrow2", bob, Vectors.of(invoke)), open, append("w/guestbook", "narrow2"))));
		complete(call(controlled(bob, "member2", bob, Vectors.of(invoke, cap(open, Abilities.CALLABLE_CALL))), open, append("w/guestbook", "member2")));
		assertEquals(Vectors.of("free", "member", "lent", "member2"), held(alice, "w/guestbook"));
	}

	@Test
	public void aCallNeverHoldsMoreThanItsCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		complete(create(owner, "narrow", "v/ops/agent/create", Vectors.of(cap("g/", Abilities.AGENT_CREATE)), "v/ops/callable/gates/anyone"));
		complete(create(owner, "whole", "v/ops/agent/create", Caps.UNRESTRICTED, "v/ops/callable/gates/anyone"));
		RequestContext narrow = engine.callContext(RequestContext.of(bob), reference(alice, "narrow"));
		RequestContext whole = engine.callContext(RequestContext.of(bob), reference(alice, "whole"));

		assertEquals(alice, narrow.getAuthorityDID(), "a call is its issuer's frame");
		assertEquals(alice, narrow.getAuthorityDID());
		assertEquals(bob, narrow.getCaller(), "called by its invoker");
		assertEquals(alice, narrow.getUserDID());
		assertFalse(engine.holdsAuthorityOf(narrow, alice));
		assertTrue(engine.holdsAuthorityOf(whole, alice), "unrestricted caps are the issuer's whole authority");
		assertFalse(engine.holdsAuthorityOf(whole, bob), "never the invoker's");
		assertEquals(alice, whole.getAuthorityDID(), "a call has its issuer's authority, so reaches state that is the issuer's");
		assertEquals(Engine.SUDO_NARROWED, assertThrows(covia.exception.AuthException.class, () -> engine.sudoContext(whole, bob)).getMessage());
		agent(alice, "worker", Caps.UNRESTRICTED);
		assertEquals(Engine.STEP_NARROWED.formatted(alice, "worker"), assertThrows(covia.exception.AuthException.class,
			() -> engine.stepContext(narrow, Strings.create("worker"))).getMessage(), "a narrower call makes none of the issuer's agents act");

		// An agent of the owner calling one another agent issued acts with the issuer's authority, never its own
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		RequestContext maker = agent(alice, "maker", Vectors.of(invoke, cap("x/callable/", Abilities.CALLABLE_MANAGE)));
		complete(create(maker, "made", "v/ops/covia/read", Vectors.of(invoke), null));
		RequestContext helper = agent(alice, "helper", Vectors.of(invoke));
		RequestContext byHelper = engine.callContext(helper, reference(alice, "made"));
		assertEquals(maker.getAuthorityDID(), Engine.actingPrincipal(byHelper));
		assertTrue(engine.actsWithAuthorityOf(byHelper, maker.getAuthorityDID()), "it acts with its issuer's authority");
		assertFalse(engine.holdsAuthorityOf(byHelper, maker.getAuthorityDID()), "narrowed, not all of it");
		assertFalse(engine.actsWithAuthorityOf(byHelper, helper.getAuthorityDID()));
		assertTrue(engine.holdsAuthorityOf(helper, helper.getAuthorityDID()), "the agent holds its own outside a call");
		assertThrows(covia.exception.AuthException.class, () -> engine.stepContext(byHelper, Strings.create("helper")),
			"nor does it make its invoker act as itself");
		assertNotNull(engine.stepContext(helper, Strings.create("helper")), "its own run makes it act");

		// A call reaches its issuer's scheduled events only as far as its caps cover them
		AString event = Strings.create(alice + "/" + Scheduler.RESOURCE_PATH + "0123456789abcdef");
		assertFalse(engine.getAccessControl().canAccessEvent(narrow, alice, event, Capability.CRUD_READ));
		assertTrue(engine.getAccessControl().canAccessEvent(whole, alice, event, Capability.CRUD_READ));

		// Its reach into another user's resources is within its caps too, whatever they admit
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "desk", Fields.CONFIG, Maps.of(
			Fields.OPERATION, "v/test/ops/echo", Fields.ACCEPTS, Vectors.of(alice))), RequestContext.of(carol)));
		AString desk = Strings.create(carol + "/g/desk");
		assertTrue(engine.authorityCovers(whole, desk, Abilities.AGENT_REQUEST), "Carol's agent admits Alice");
		assertFalse(engine.authorityCovers(narrow, desk, Abilities.AGENT_REQUEST));

		// An agent a call creates takes the issuer as controller, so only a call holding all of it may
		String made = failure(call(RequestContext.of(bob), alice + "/x/callable/narrow", Maps.of(Fields.AGENT_ID, "made")));
		assertEquals(Engine.CONTROLLER_NOT_HELD.formatted(alice), made);
		complete(call(RequestContext.of(bob), alice + "/x/callable/whole", Maps.of(Fields.AGENT_ID, "made")));
		assertNotNull(engine.getAgent(alice, Strings.create("made")));

		// Even the venue calling the venue's own callable does not act as the venue
		AString venue = engine.getDIDString();
		complete(create(engine.venueContext(), unique("venue", info), "v/ops/covia/read", Caps.UNRESTRICTED, null));
		RequestContext asVenue = engine.callContext(engine.venueContext(), reference(venue, unique("venue", info)));
		assertFalse(engine.isDirectVenue(asVenue));
		assertTrue(engine.isDirectVenue(engine.venueContext()));
	}

	@Test
	public void workACallLeavesRunningKeepsTheCallable(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		complete(create(owner, "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		AMap<AString, ACell> ref = reference(alice, "guestbook");

		AMap<AString, ACell> kept = Engine.keptAuthority(engine.callContext(RequestContext.of(bob), ref));
		assertEquals(Maps.of(Fields.CALLABLE, ref, Fields.CALLER, bob), kept);
		RequestContext resumed = engine.context(alice, alice, null, kept);
		assertEquals(alice, resumed.getAuthorityDID(), "it resumes in the issuer's frame");
		assertEquals(bob, resumed.getCaller(), "called by the invoker");
		assertNull(resumed.getOrigin(), "with nobody present");
		assertTrue(mayWrite(resumed, alice, "w/guestbook"));
		assertFalse(mayWrite(resumed, alice, "w/private"));

		// Narrowing the callable narrows the work, read at each check
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.CAPS, Vectors.empty()), owner));
		assertFalse(mayWrite(resumed, alice, "w/guestbook"));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.CAPS, GUESTBOOK_CAPS), owner));
		assertTrue(mayWrite(resumed, alice, "w/guestbook"));

		// Deleted, it narrows to nothing, and a new callable of that name is another callable
		complete(run("v/ops/callable/delete", Maps.of(Fields.NAME, "guestbook"), owner));
		assertFalse(mayWrite(resumed, alice, "w/guestbook"));
		complete(create(owner, "guestbook", "v/ops/covia/append", Caps.UNRESTRICTED, null));
		assertFalse(mayWrite(resumed, alice, "w/guestbook"));
		assertFalse(mayWrite(engine.context(alice, alice, null, kept), alice, "w/guestbook"));
	}

	@Test
	public void anEventACallSchedulesFiresWithTheCallablesAuthority(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		// Firing an event invokes its operation, so the caps name that too
		AVector<ACell> caps = GUESTBOOK_CAPS.conj(cap("v/ops/covia/append", Abilities.INVOKE));
		complete(create(owner, "later", "v/ops/scheduler/schedule", caps, "v/ops/callable/gates/anyone"));
		String later = alice + "/x/callable/later";
		ACell first = schedule(bob, later, append("w/guestbook", "Later from Bob"));
		ACell beyond = schedule(bob, later, append("w/private", "x"));
		ACell second = schedule(bob, later, append("w/guestbook", "After deletion"));

		// The events are Alice's work, made in her callable's call: Bob reaches none of them
		AVector<ACell> events = RT.ensureVector(RT.getIn(complete(run("v/ops/scheduler/list", Maps.empty(), owner)), "events"));
		assertEquals(3, events.count());
		for (ACell event : events) assertEquals(alice, RT.getIn(event, Fields.CALLER));
		failure(run("v/ops/scheduler/cancel", Maps.of("handle", first), RequestContext.of(bob)));

		// Each fires with the callable's authority as it stands then
		complete(run("v/ops/scheduler/trigger", Maps.of("handle", first), owner));
		failure(run("v/ops/scheduler/trigger", Maps.of("handle", beyond), owner));
		complete(run("v/ops/callable/delete", Maps.of(Fields.NAME, "later"), owner));
		failure(run("v/ops/scheduler/trigger", Maps.of("handle", second), owner));
		assertEquals(Vectors.of("Later from Bob"), held(alice, "w/guestbook"));
		assertNull(held(alice, "w/private"));
	}

	@Test
	public void aCallableATokenMadeKeepsItsOwnCaps(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		AVector<ACell> tools = Vectors.of("v/ops/callable/create", "v/ops/callable/update");
		ACell tokenCaps = Vectors.of(cap("x/callable/", Abilities.CALLABLE_MANAGE), cap("w/guestbook", Capability.CRUD_WRITE));
		String bearer = RT.getIn(complete(run("v/ops/mcp/create-token",
			Maps.of(Fields.NAME, "maker", Fields.TOOLS, tools, Fields.CAPS, tokenCaps), owner)), Fields.TOKEN).toString();
		AMap<AString, ACell> token = engine.tokens().lookup(bearer);

		// The token's request gives no caps beyond the token's own
		ExecutionException beyond = assertThrows(ExecutionException.class, () -> throughToken(token, "v/ops/callable/create",
			Maps.of(Fields.NAME, "made", Fields.OPERATION, "v/ops/covia/append", Fields.CAPS, Caps.UNRESTRICTED,
				Fields.GATE, "v/ops/callable/gates/anyone")).get(10, TimeUnit.SECONDS));
		assertTrue(beyond.getCause().getMessage().startsWith(Engine.CAPS_BEYOND_GIVER), beyond.getCause()::getMessage);

		// A callable it makes within them acts within its own caps
		throughToken(token, "v/ops/callable/create", Maps.of(Fields.NAME, "made", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Vectors.of(cap("w/guestbook", Capability.CRUD_WRITE)), Fields.GATE, "v/ops/callable/gates/anyone"))
			.get(10, TimeUnit.SECONDS);
		String made = alice + "/x/callable/made";
		complete(call(RequestContext.of(bob), made, append("w/guestbook", "through the token")));
		failure(call(RequestContext.of(bob), made, append("w/private", "x")));

		// The token changes a callable within its caps, never a wider one
		complete(create(owner, "direct", "v/ops/covia/append", Caps.UNRESTRICTED, null));
		ExecutionException refused = assertThrows(ExecutionException.class, () -> throughToken(token, "v/ops/callable/update",
			Maps.of(Fields.NAME, "direct", Fields.CAPS, Vectors.empty())).get(10, TimeUnit.SECONDS));
		assertEquals(CallableAdapter.CALLABLE_NOT_COVERED.formatted("direct"), refused.getCause().getMessage());
		throughToken(token, "v/ops/callable/update", Maps.of(Fields.NAME, "made", Fields.DESCRIPTION, "Sign here"))
			.get(10, TimeUnit.SECONDS);

		// What the token made stands once it is gone, within its own caps
		complete(run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "maker"), owner));
		complete(call(RequestContext.of(bob), made, append("w/guestbook", "after the token")));
		failure(call(RequestContext.of(bob), made, append("w/private", "x")));
		assertEquals(Vectors.of("through the token", "after the token"), held(alice, "w/guestbook"));
	}

	/** {@code caller} has callable {@code callable} schedule an append of {@code append} an hour from now, and gets its handle. */
	private ACell schedule(AString caller, String callable, ACell append) {
		ACell event = Maps.of(Fields.OPERATION, "v/ops/covia/append", Fields.INPUT, append, "after", 3_600_000L);
		return RT.getIn(complete(call(RequestContext.of(caller), callable, event)), "handle");
	}

	/** Tool {@code ref} called through access token {@code token}, as the venue's MCP endpoint calls it. */
	private CompletableFuture<ACell> throughToken(AMap<AString, ACell> token, String ref, ACell input) {
		AccessToken standing = engine.resolveToken(token);
		Operation operation = engine.jobs().resolveOperation(Strings.create(ref), engine.context(standing.owner(), standing.issuer()));
		return engine.jobs().startToolOperation(Strings.create(ref), operation, input, engine.tokenContext(token)).result();
	}

	@Test
	public void whatACallStartsIsItsIssuersWork(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		complete(create(owner, "hold", "v/test/ops/never", Caps.UNRESTRICTED, null));
		RequestContext call = engine.callContext(RequestContext.of(bob), reference(alice, "hold"));
		Job started = engine.jobs().invokeOperation("v/test/ops/never", Maps.empty(), call);
		try {
			assertEquals(alice, started.getCaller(), "the issuer's, not the invoker's");
			assertNull(started.getData().get(Fields.OWNER), "kept in the issuer's own account");
			assertNotNull(engine.jobs().getJobData(started.getID(), owner), "the issuer reaches it");
			assertThrows(covia.exception.AuthException.class,
				() -> engine.jobs().getJobData(started.getID(), RequestContext.of(bob)), "the invoker does not");
		} finally {
			started.cancel();
		}
	}

	@Test
	public void callsNestEachInItsIssuersFrame(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		complete(create(owner, "inner", "v/ops/covia/append", GUESTBOOK_CAPS, "v/ops/callable/gates/anyone"));
		complete(create(owner, "outer", "v/ops/callable/call", Caps.UNRESTRICTED, "v/ops/callable/gates/anyone"));
		String innerPath = alice + "/x/callable/inner";
		AMap<AString, ACell> inner = Maps.of(Fields.CALLABLE, innerPath, Fields.INPUT, append("w/guestbook", "nested"));

		// A call calls a callable
		complete(call(RequestContext.of(carol), alice + "/x/callable/outer", inner));
		assertEquals(Vectors.of("nested"), held(alice, "w/guestbook"));

		// Each call's caller is the authority that made it, and only its own callable narrows it
		RequestContext outerFrame = engine.callContext(RequestContext.of(carol), reference(alice, "outer"));
		RequestContext innerFrame = engine.callContext(outerFrame, reference(alice, "inner"));
		assertEquals(carol, outerFrame.getCaller());
		assertEquals(alice, innerFrame.getCaller());
		assertEquals(reference(alice, "inner"), innerFrame.getCallable());
		assertEquals(carol, innerFrame.getOrigin(), "who is present goes on through both");
		// An agent's tool call acts as the agent (Engine.stepContext), so the agent is the call's caller
		AString bobsAgent = covia.grid.Principals.agentDID(bob, Strings.create("helper"));
		assertEquals(bobsAgent, engine.callContext(engine.context(bob, bobsAgent), reference(alice, "outer")).getCaller());

		// The inner callable's caps bound the inner call, however wide the outer's
		failure(call(RequestContext.of(carol), alice + "/x/callable/outer",
			Maps.of(Fields.CALLABLE, innerPath, Fields.INPUT, append("w/private", "x"))));
		assertNull(held(alice, "w/private"));

		// Bob's callable calls Alice's only as far as its caps cover calling it, and is not shown it otherwise
		RequestContext bobs = RequestContext.of(bob);
		complete(create(bobs, "relay", "v/ops/callable/call", Vectors.of(cap("w/", Capability.CRUD)), "v/ops/callable/gates/anyone"));
		String relay = bob + "/x/callable/relay";
		assertEquals(CallableAdapter.NOT_CALLABLE.formatted(innerPath), failure(call(RequestContext.of(carol), relay, inner)));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "relay",
			Fields.CAPS, Vectors.of(cap(innerPath, Abilities.CALLABLE_CALL))), bobs));
		complete(call(RequestContext.of(carol), relay, inner));
		assertEquals(Vectors.of("nested", "nested"), held(alice, "w/guestbook"));

		// The inner call holds none of Bob's authority, nor Carol's
		failure(call(RequestContext.of(carol), relay,
			Maps.of(Fields.CALLABLE, innerPath, Fields.INPUT, append(bob + "/w/guestbook", "x"))));
		failure(call(RequestContext.of(carol), relay,
			Maps.of(Fields.CALLABLE, innerPath, Fields.INPUT, append(carol + "/w/guestbook", "x"))));
		assertNull(held(bob, "w/guestbook"));
		assertNull(held(carol, "w/guestbook"));
	}

	@Test
	public void aUserManagesItsCallablesAndSeesThemAtXCallable(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		complete(create(owner, "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, "v/ops/callable/gates/anyone"));
		Callable stored = callables().callable(alice, Strings.create("guestbook"));

		AMap<AString, ACell> view = RT.ensureMap(RT.getIn(complete(run("v/ops/covia/read", Maps.of(Fields.PATH, "x/callable"), owner)), Fields.VALUE));
		AMap<AString, ACell> shown = RT.ensureMap(view.get(Strings.create("guestbook")));
		assertEquals(alice, shown.get(Fields.ISSUER));
		assertEquals(Strings.create("v/ops/covia/append"), shown.get(Fields.OPERATION));
		assertEquals(GUESTBOOK_CAPS, shown.get(Fields.CAPS));
		assertFalse(view.toString().contains(stored.id().toString()), "never its id");
		assertEquals(1L, RT.ensureLong(RT.getIn(complete(run("v/ops/callable/list", Maps.empty(), owner)), Fields.TOTAL)).longValue());
		failure(run("v/ops/covia/write", Maps.of(Fields.PATH, "x/callable/guestbook", Fields.VALUE, 1), owner));
		assertEquals(CallableAdapter.CALLABLE_EXISTS.formatted("guestbook"),
			failure(create(owner, "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, null)));

		// An agent reads them with crud/read, and manages them with callable/manage
		failure(run("v/ops/callable/list", Maps.empty(), agent(alice, "blind", Vectors.of(invoke))));
		complete(run("v/ops/callable/list", Maps.empty(), agent(alice, "sighted", Vectors.of(invoke, cap("x/callable", Capability.CRUD_READ)))));
		RequestContext manager = agent(alice, "manager", Vectors.of(invoke, cap("x/callable/", Abilities.CALLABLE_MANAGE), cap("w/", Capability.CRUD)));
		failure(create(agent(alice, "unmanaging", Vectors.of(invoke)), "other", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		assertTrue(failure(create(manager, "unbounded", "v/ops/covia/append", Caps.UNRESTRICTED, null))
			.startsWith(Engine.CAPS_BEYOND_GIVER), "it gives no caps beyond its own");
		complete(create(manager, "managed", "v/ops/covia/append", GUESTBOOK_CAPS, null));

		// Changing a callable needs its issuer's authority covering its caps; deleting one does not
		assertEquals(CallableAdapter.CALLABLE_NOT_COVERED.formatted("guestbook"),
			failure(run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.CAPS, Caps.UNRESTRICTED), manager)));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "managed", Fields.CAPS, Vectors.empty()), manager));
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.DESCRIPTION, "Sign here"), owner));
		assertEquals(Strings.create("Sign here"), RT.getIn(complete(run("v/ops/covia/read",
			Maps.of(Fields.PATH, "x/callable/guestbook"), owner)), Fields.VALUE, Fields.DESCRIPTION));
		complete(run("v/ops/callable/delete", Maps.of(Fields.NAME, "guestbook"), manager));
		assertNull(callables().callable(alice, Strings.create("guestbook")).record());

		// An agent's callables go with it
		complete(run("v/ops/agent/delete", Maps.of(Fields.AGENT_ID, "manager", Fields.REMOVE, true), owner));
		assertNull(callables().callable(alice, Strings.create("managed")).record());
	}

	/** {@code owner}'s gate at {@code o/team-gate}, admitting {@code callers} and the agents acting for them. */
	private void teamGate(AString owner, AString... callers) {
		complete(run("v/ops/covia/write", Maps.of(Fields.PATH, "o/team-gate", Fields.VALUE, Maps.of(
			Fields.NAME, "Team gate",
			Fields.OPERATION, Maps.of(Fields.ADAPTER, "callable:gates/callers",
				Fields.DEFAULT, Maps.of(Fields.CALLERS, Vectors.of((Object[]) callers))))), RequestContext.of(owner)));
	}

	/** Invoking {@code ref} as {@code ctx}, refused before any job is made. */
	private NotFoundException unresolved(String ref, ACell input, RequestContext ctx) {
		return assertThrows(NotFoundException.class, () -> engine.jobs().invokeOperation(ref, input, ctx));
	}

	/** The input schema operation {@code ref} declares, as {@code ctx} resolves it. */
	private ACell inputSchema(String ref, RequestContext ctx) {
		return RT.getIn(engine.jobs().resolveOperation(Strings.create(ref), ctx).meta(), Fields.OPERATION, Fields.INPUT);
	}

	@Test
	public void aCallableIsInvokedAsAnOperationByItsPath(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		AString carol = TestEngine.uniqueDID("carol-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		RequestContext bobs = RequestContext.of(bob);
		openGuestbook(alice);
		teamGate(alice, bob);
		complete(create(owner, "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, "o/team-gate"));
		String guestbook = alice + "/x/callable/guestbook";

		// The job is Bob's and takes the target's input; the target writes in Alice's namespace
		Job job = run(guestbook, append("w/guestbook", "by path"), bobs);
		complete(job);
		assertEquals(bob, job.getCaller());
		assertEquals(bob, JobManager.accountOf(job), "in the invoker's account");
		assertEquals(Strings.create(guestbook), job.getData().get(Fields.OP));
		assertEquals(append("w/guestbook", "by path"), job.getData().get(Fields.INPUT), "the target's input, not wrapped");
		assertEquals(Vectors.of("by path"), held(alice, "w/guestbook"));
		assertNull(held(bob, "w/guestbook"));
		assertEquals(inputSchema("v/ops/covia/append", bobs), inputSchema(guestbook, bobs), "its schema is the target's");

		// The callable's caps bound it, and its gate decides on the call's input
		failure(run(guestbook, append("w/private", "x"), bobs));
		assertNull(held(alice, "w/private"));
		String refused = failure(run(guestbook, append("w/guestbook", "from Carol"), RequestContext.of(carol)));
		assertTrue(refused.contains(CallableAdapter.NOT_A_CALLER.formatted(carol)), refused);

		// Wherever an operation is named: grid:run, a run, a scheduled event, an orchestration's step, strict or not
		complete(run("v/ops/grid/run", Maps.of(Fields.OPERATION, guestbook, Fields.INPUT,
			append("w/guestbook", "through grid:run")), bobs));
		engine.jobs().runOperation(Strings.create(guestbook), append("w/guestbook", "through a run"), bobs).get(10, TimeUnit.SECONDS);
		ACell handle = RT.getIn(complete(run("v/ops/scheduler/schedule", Maps.of(Fields.OPERATION, guestbook,
			Fields.INPUT, append("w/guestbook", "when scheduled"), "after", 3_600_000L), bobs)), "handle");
		complete(run("v/ops/scheduler/trigger", Maps.of("handle", handle), bobs));
		for (boolean strict : new boolean[] {false, true}) {
			AMap<AString, ACell> step = Maps.of("op", guestbook, Fields.INPUT, Maps.of(
				Fields.PATH, Vectors.of("const", "w/guestbook"), Fields.VALUE, Vectors.of("const", "from a step")));
			complete(run("v/ops/covia/write", Maps.of(Fields.PATH, "o/sign", Fields.VALUE, Maps.of(
				Fields.NAME, "Sign", Fields.OPERATION, Maps.of(Fields.ADAPTER, "orchestrator",
					"steps", Vectors.of(strict ? step.assoc(Strings.create("strict"), CVMBool.TRUE) : step),
					"result", Vectors.of(0L)))), bobs));
			complete(run("o/sign", Maps.empty(), bobs));
		}

		// The owner invokes its own by the bare path, which resolves in its namespace
		complete(run("x/callable/guestbook", append("w/guestbook", "from Alice"), owner));
		assertEquals(Vectors.of("by path", "through grid:run", "through a run", "when scheduled", "from a step", "from a step",
			"from Alice"), held(alice, "w/guestbook"));

		// Reading the path is unchanged: the owner's view of the callable
		assertEquals(Strings.create("v/ops/covia/append"), RT.getIn(complete(run("v/ops/covia/read",
			Maps.of(Fields.PATH, "x/callable/guestbook"), owner)), Fields.VALUE, Fields.OPERATION));
	}

	@Test
	public void aCallerNotAdmittedIsRefusedAsIfTheCallableDidNotExist(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		RequestContext bobs = RequestContext.of(bob);
		openGuestbook(alice);
		complete(create(owner, "private", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		complete(create(owner, "open", "v/ops/covia/append", GUESTBOOK_CAPS, "v/ops/callable/gates/anyone"));
		String hidden = alice + "/x/callable/private";
		String missing = alice + "/x/callable/missing";
		String open = alice + "/x/callable/open";
		ACell signing = append("w/guestbook", "x");

		// A callable without a gate that the caller may not call is refused as a missing operation is
		assertEquals(JobManager.CANNOT_RESOLVE_OPERATION + hidden, unresolved(hidden, signing, bobs).getMessage());
		assertEquals(JobManager.CANNOT_RESOLVE_OPERATION + missing, unresolved(missing, signing, bobs).getMessage());
		assertEquals(JobManager.CANNOT_RESOLVE_OPERATION + "x/callable/private",
			unresolved("x/callable/private", signing, bobs).getMessage(), "the bare path is in the caller's namespace");
		complete(run(hidden, append("w/guestbook", "from Alice"), owner));

		// A gate admits a capped agent only as far as its caps cover calling: otherwise there is no such operation
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		RequestContext narrow = controlled(bob, "narrow", bob, Vectors.of(invoke, cap(open, Abilities.INVOKE)));
		assertEquals(JobManager.CANNOT_RESOLVE_OPERATION + open, unresolved(open, signing, narrow).getMessage());

		// Invoking the path needs invoke on it, as invoking any operation by its path does
		RequestContext caller = controlled(bob, "caller", bob, Vectors.of(invoke, cap(open, Abilities.CALLABLE_CALL)));
		failure(run(open, append("w/guestbook", "without invoke"), caller));
		RequestContext invoker = controlled(bob, "invoker", bob,
			Vectors.of(invoke, cap(open, Abilities.CALLABLE_CALL), cap(open, Abilities.INVOKE)));
		complete(run(open, append("w/guestbook", "from Bob's agent"), invoker));
		assertEquals(Vectors.of("from Alice", "from Bob's agent"), held(alice, "w/guestbook"));
	}

	@Test
	public void aCallablesPathMayNameAnotherAndCallablesThatLeadBackNameNoOperation(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		complete(create(owner, "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		complete(create(owner, "front", "x/callable/guestbook", Vectors.of(cap("x/callable/guestbook", Abilities.CALLABLE_CALL)),
			"v/ops/callable/gates/anyone"));
		String front = alice + "/x/callable/front";

		// One callable's path names another, whose call runs within the outer one, with the inner target's schema
		complete(run(front, append("w/guestbook", "through the front"), RequestContext.of(bob)));
		assertEquals(Vectors.of("through the front"), held(alice, "w/guestbook"));
		assertEquals(inputSchema("v/ops/covia/append", owner), inputSchema(front, RequestContext.of(bob)));

		// A callable whose target leads back to it names no operation, and is refused as one that does not resolve
		complete(run("v/ops/callable/update", Maps.of(Fields.NAME, "guestbook", Fields.OPERATION, "x/callable/front"), owner));
		assertEquals(JobManager.CANNOT_RESOLVE_OPERATION + front, unresolved(front, append("w/guestbook", "x"), RequestContext.of(bob)).getMessage());
		assertEquals(Vectors.of("through the front"), held(alice, "w/guestbook"));
	}

	@Test
	public void anAgentCallsACallableByItsPathAsATool(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext bobs = RequestContext.of(bob);
		openGuestbook(alice);
		teamGate(alice, bob);
		complete(create(RequestContext.of(alice), "guestbook", "v/ops/covia/append", GUESTBOOK_CAPS, "o/team-gate"));
		String guestbook = alice + "/x/callable/guestbook";

		// Bob's agent is offered the callable as a tool named after it, taking the target's input
		AMap<AString, ACell> config = Maps.of(Fields.OPERATION, "v/ops/llmagent/chat", "llmOperation", "v/test/ops/llm",
			Fields.TOOLS, Vectors.of(guestbook));
		complete(run("v/ops/agent/create", Maps.of(Fields.AGENT_ID, "signer", Fields.CONFIG, TestEngine.unrestricted(config)), bobs));
		ACell session = RT.getIn(complete(run("v/ops/agent/chat", Maps.of(Fields.AGENT_ID, "signer", Fields.MESSAGE, "hello"), bobs)),
			Fields.SESSION_ID);
		AMap<AString, ACell> toolCall = Maps.of("id", "sign", "name", "guestbook",
			"arguments", append("w/guestbook", "from Bob's agent"));
		AMap<AString, ACell> step = RT.ensureMap(complete(run("v/ops/agent/step", Maps.of(Fields.AGENT_ID, "signer",
			Fields.SESSION_ID, session, "assistant", Maps.of("toolCalls", Vectors.of(toolCall))), bobs)));

		ACell call = RT.ensureVector(step.get(Fields.CALLS)).get(0);
		assertFalse(String.valueOf(RT.getIn(call, Fields.RESULT)).startsWith("Error"), call::toString);
		assertEquals(Vectors.of("from Bob's agent"), held(alice, "w/guestbook"));
		ACell offered = null;
		for (ACell tool : RT.ensureVector(RT.getIn(step, covia.adapter.agent.AbstractLLMAdapter.K_NEXT, Fields.TOOLS))) {
			if ("guestbook".equals(String.valueOf(RT.getIn(tool, Fields.NAME)))) offered = tool;
		}
		assertNotNull(offered, step::toString);
		assertEquals(inputSchema("v/ops/covia/append", bobs), RT.getIn(offered, covia.adapter.agent.AbstractLLMAdapter.K_PARAMETERS));
	}

	private static String unique(String prefix, TestInfo info) {
		return (prefix + "-" + Integer.toHexString(info.getDisplayName().hashCode())).toLowerCase();
	}
}

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
import convex.core.lang.RT;
import covia.adapter.token.AccessToken;
import covia.api.Abilities;
import covia.api.Fields;
import covia.grid.Job;
import covia.grid.Operation;
import covia.grid.Principals;
import covia.grid.Status;
import covia.lattice.Caps;
import covia.venue.Engine;
import covia.venue.RequestContext;
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
		assertEquals(bob, called.getData().get(Fields.CALLER), "the call is the invoker's");

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
		complete(call(agent(bob, "helper", Vectors.of(cap("v/ops/", Abilities.INVOKE))), callable, append("w/guestbook", "from Bob's agent")));
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

	@Test
	public void aCallNeverHoldsMoreThanItsCaps(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		complete(create(owner, "narrow", "v/ops/agent/create", Vectors.of(cap("g/", Abilities.AGENT_CREATE)), "v/ops/callable/gates/anyone"));
		complete(create(owner, "whole", "v/ops/agent/create", Caps.UNRESTRICTED, "v/ops/callable/gates/anyone"));
		RequestContext narrow = engine.callContext(RequestContext.of(bob), reference(alice, "narrow"));
		RequestContext whole = engine.callContext(RequestContext.of(bob), reference(alice, "whole"));

		assertEquals(bob, narrow.getCallerDID());
		assertEquals(alice, narrow.getUserDID());
		assertFalse(engine.holdsAuthorityOf(narrow, alice));
		assertTrue(engine.holdsAuthorityOf(whole, alice), "unrestricted caps are the issuer's whole authority");
		assertFalse(engine.holdsAuthorityOf(whole, bob), "never the invoker's");
		assertFalse(whole.actsWithNamespaceAuthority(), "state held for a namespace is never a call's");
		assertEquals(Engine.SUDO_NARROWED, assertThrows(covia.exception.AuthException.class, () -> engine.sudoContext(whole, bob)).getMessage());

		// An agent of the owner calling one another agent issued acts with the issuer's authority, never its own
		ACell invoke = cap("v/ops/", Abilities.INVOKE);
		RequestContext maker = agent(alice, "maker", Vectors.of(invoke, cap("x/callable/", Abilities.CALLABLE_MANAGE)));
		complete(create(maker, "made", "v/ops/covia/read", Caps.UNRESTRICTED, null));
		RequestContext helper = agent(alice, "helper", Vectors.of(invoke));
		RequestContext byHelper = engine.callContext(helper, reference(alice, "made"));
		assertEquals(maker.getCallerDID(), Engine.actingPrincipal(byHelper));
		assertTrue(engine.holdsAuthorityOf(byHelper, maker.getCallerDID()), "unrestricted, it is all of its issuer's");
		assertFalse(engine.holdsAuthorityOf(byHelper, helper.getCallerDID()));
		assertTrue(engine.holdsAuthorityOf(helper, helper.getCallerDID()), "the agent holds its own outside a call");
		assertThrows(covia.exception.AuthException.class, () -> engine.stepContext(byHelper, Strings.create("helper")),
			"nor does it make its invoker act as itself");
		assertNotNull(engine.stepContext(helper, Strings.create("helper")), "its own run makes it act");

		// What a call's caller made in the owner's namespace is not the call's by attribution
		AMap<AString, ACell> event = Maps.of(Fields.CALLER, bob, Fields.OWNER, alice);
		assertFalse(engine.getAccessControl().canAccessEvent(narrow, event));
		assertTrue(engine.getAccessControl().canAccessEvent(RequestContext.of(bob, alice, Vectors.empty()), event),
			"a principal acting in the namespace reaches what it made there");

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
		assertEquals(Maps.of(Fields.CALLABLE, ref), kept);
		RequestContext resumed = engine.context(alice, bob, null, kept);
		assertEquals(bob, resumed.getCallerDID(), "it resumes with the invoker as its caller");
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
		assertFalse(mayWrite(engine.context(alice, bob, null, kept), alice, "w/guestbook"));
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

		// The events are Alice's, made by Bob
		AVector<ACell> events = RT.ensureVector(RT.getIn(complete(run("v/ops/scheduler/list", Maps.empty(), owner)), "events"));
		assertEquals(3, events.count());
		for (ACell event : events) assertEquals(bob, RT.getIn(event, Fields.CALLER));

		// Each fires with the callable's authority as it stands then
		complete(run("v/ops/scheduler/trigger", Maps.of("handle", first), owner));
		failure(run("v/ops/scheduler/trigger", Maps.of("handle", beyond), owner));
		complete(run("v/ops/callable/delete", Maps.of(Fields.NAME, "later"), owner));
		failure(run("v/ops/scheduler/trigger", Maps.of("handle", second), owner));
		assertEquals(Vectors.of("Later from Bob"), held(alice, "w/guestbook"));
		assertNull(held(alice, "w/private"));
	}

	@Test
	public void aCallableATokenMadeIsNarrowedByThatToken(TestInfo info) throws Exception {
		AString alice = TestEngine.uniqueDID(info);
		AString bob = TestEngine.uniqueDID("bob-" + info.getDisplayName());
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		AVector<ACell> tools = Vectors.of("v/ops/callable/create", "v/ops/callable/update");
		ACell tokenCaps = Vectors.of(cap("x/callable/", Abilities.CALLABLE_MANAGE), cap("w/guestbook", Capability.CRUD_WRITE));
		String bearer = RT.getIn(complete(run("v/ops/mcp/create-token",
			Maps.of(Fields.NAME, "maker", Fields.TOOLS, tools, Fields.CAPS, tokenCaps), owner)), Fields.TOKEN).toString();
		AMap<AString, ACell> token = engine.tokens().lookup(bearer);

		// An unrestricted callable the token's request makes is still narrowed by the token
		throughToken(token, "v/ops/callable/create", Maps.of(Fields.NAME, "made", Fields.OPERATION, "v/ops/covia/append",
			Fields.CAPS, Caps.UNRESTRICTED, Fields.GATE, "v/ops/callable/gates/anyone")).get(10, TimeUnit.SECONDS);
		String made = alice + "/x/callable/made";
		complete(call(RequestContext.of(bob), made, append("w/guestbook", "through the token")));
		failure(call(RequestContext.of(bob), made, append("w/private", "x")));

		// The token changes what it made, never what Alice made directly
		complete(create(owner, "direct", "v/ops/covia/append", Caps.UNRESTRICTED, null));
		ExecutionException refused = assertThrows(ExecutionException.class, () -> throughToken(token, "v/ops/callable/update",
			Maps.of(Fields.NAME, "direct", Fields.CAPS, Vectors.empty())).get(10, TimeUnit.SECONDS));
		assertEquals(CallableAdapter.SOURCE_NOT_HELD.formatted("direct"), refused.getCause().getMessage());
		throughToken(token, "v/ops/callable/update", Maps.of(Fields.NAME, "made", Fields.DESCRIPTION, "Sign here"))
			.get(10, TimeUnit.SECONDS);

		// Deleted, the token narrows what it made to nothing
		complete(run("v/ops/mcp/delete-token", Maps.of(Fields.NAME, "maker"), owner));
		failure(call(RequestContext.of(bob), made, append("w/guestbook", "after the token")));
		assertEquals(Vectors.of("through the token"), held(alice, "w/guestbook"));
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
	public void aCallCannotCallACallable(TestInfo info) {
		AString alice = TestEngine.uniqueDID(info);
		RequestContext owner = RequestContext.of(alice);
		openGuestbook(alice);
		complete(create(owner, "inner", "v/ops/covia/append", GUESTBOOK_CAPS, null));
		complete(create(owner, "outer", "v/ops/callable/call", Caps.UNRESTRICTED, null));

		AMap<AString, ACell> inner = Maps.of(Fields.CALLABLE, "inner", Fields.INPUT, append("w/guestbook", "x"));
		assertEquals(CallableAdapter.CALL_WITHIN_CALL, failure(call(owner, "outer", inner)));
		complete(run("v/ops/callable/call", inner, owner));
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
		complete(create(manager, "managed", "v/ops/covia/append", Caps.UNRESTRICTED, null));

		// Changing a callable needs the authority it was created with; deleting one does not
		assertEquals(CallableAdapter.SOURCE_NOT_HELD.formatted("guestbook"),
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

	private static String unique(String prefix, TestInfo info) {
		return (prefix + "-" + Integer.toHexString(info.getDisplayName().hashCode())).toLowerCase();
	}
}

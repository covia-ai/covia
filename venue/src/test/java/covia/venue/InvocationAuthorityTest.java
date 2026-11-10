package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import covia.api.Abilities;
import covia.grid.Job;
import covia.grid.Status;

/** Arbitrary reference invocation and a bound tool are distinct authorities. */
public class InvocationAuthorityTest {
	private final Engine engine = TestEngine.ENGINE;
	private RequestContext owner;
	private static final AString WRITE = Strings.create("v/ops/covia/write");
	private static final AString ECHO = Strings.create("v/test/ops/echo");

	@BeforeEach void caller(TestInfo info) {
		owner = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private static AMap<AString, ACell> cap(String resource, AString ability) {
		return Capability.create(Strings.create(resource), ability);
	}

	private static void denied(Job job, AString ability) {
		assertThrows(Exception.class, () -> job.awaitResult(5000));
		assertEquals(Status.FAILED, job.getStatus());
		assertTrue(job.getErrorMessage().contains(ability.toString()), job.getErrorMessage());
	}

	@Test void directWriteNeedsOnlyResourceAuthority() {
		var write = cap("w/", Capability.CRUD_WRITE);
		AMap<AString, ACell> input = Maps.of("path", "w/result", "value", 42);
		RequestContext writer = owner.withCaps(Vectors.of(write));
		assertNotNull(engine.jobs().runToolOperation(WRITE, input, writer).join());
		denied(engine.jobs().invokeOperation(WRITE, input, writer), Abilities.INVOKE);
		assertNotNull(engine.jobs().invokeOperation(WRITE, input, owner.withCaps(Vectors.of(
			write, cap("v/ops/covia", Abilities.INVOKE)))).awaitResult(5000));
	}

	@Test void invokeDoesNotGrantWriteAndDirectCallsStillCheckWrite() {
		RequestContext lookup = owner.withCaps(Vectors.of(cap("v/ops", Abilities.INVOKE)));
		AMap<AString, ACell> input = Maps.of("path", "w/result", "value", 42);
		denied(engine.jobs().invokeOperation(WRITE, input, lookup), Capability.CRUD_WRITE);
		denied(engine.jobs().startToolOperation(WRITE, input, lookup).job(), Capability.CRUD_WRITE);
	}

	@Test void pureBoundToolNeedsNoInvokeButGenericRunDoes() {
		RequestContext empty = owner.withCaps(Vectors.empty());
		AMap<AString, ACell> input = Maps.of("values", Vectors.of(Maps.of("answer", 42)));
		AString merge = Strings.create("v/ops/json/merge");
		assertNotNull(engine.jobs().runToolOperation(merge, input, empty).join());
		denied(engine.jobs().invokeOperation(merge, input, empty), Abilities.INVOKE);
		assertThrows(Exception.class, () -> engine.jobs().runOperation(merge, input, empty, false).join());
	}

	@Test void locationGrantDoesNotFollowAliasesOrHashes() {
		RequestContext lookup = owner.withCaps(Vectors.of(cap("v/test/ops", Abilities.INVOKE)));
		assertNotNull(engine.jobs().invokeOperation(ECHO, Maps.of("echo", "ok"), lookup).awaitResult(5000));
		var meta = engine.jobs().resolveOperation(ECHO, owner).meta();
		AString hash = engine.storeAsset(Strings.create(convex.core.util.JSON.toString(meta)), null).toCVMHexString();
		denied(engine.jobs().invokeOperation(hash, Maps.empty(), lookup), Abilities.INVOKE);
		denied(engine.jobs().invokeOperation(ECHO, Maps.empty(), owner.withCaps(Vectors.of(
			cap("v/test/op", Abilities.INVOKE)))), Abilities.INVOKE);
	}

	@Test void directGridToolChecksItsTargetAndNestedCallsCannotBorrowWrapperPermission() {
		AString run = Strings.create("v/ops/grid/run");
		AMap<AString, ACell> input = Maps.of("operation", ECHO, "input", Maps.of("echo", "ok"));
		RequestContext targetOnly = owner.withCaps(Vectors.of(cap(ECHO.toString(), Abilities.INVOKE)));
		assertNotNull(engine.jobs().runToolOperation(run, input, targetOnly).join());
		RequestContext wrapperOnly = owner.withCaps(Vectors.of(cap(run.toString(), Abilities.INVOKE)));
		denied(engine.jobs().startToolOperation(run, input, wrapperOnly).job(), Abilities.INVOKE);
		denied(engine.jobs().invokeOperation(run, input, wrapperOnly), Abilities.INVOKE);
		var remote = input.assoc(Strings.create("venue"), Strings.create("http://10.0.0.1"));
		denied(engine.jobs().startToolOperation(run, remote, wrapperOnly).job(), Abilities.INVOKE);
	}

	@Test void remoteInvokeGateSeesTargetInput() {
		var grant = cap(ECHO.toString(), Abilities.INVOKE).assoc(Capability.NB,
			Maps.of("gate", "v/test/ops/amountgate"));
		RequestContext ctx = owner.withCaps(Vectors.of(grant))
			.withOp(Strings.create("v/ops/grid/run"));
		assertDoesNotThrow(() -> engine.jobs().requireRemoteInvoke(ECHO, Maps.of("amount", 840), ctx));
		assertThrows(covia.exception.AuthException.class,
			() -> engine.jobs().requireRemoteInvoke(ECHO, Maps.of("amount", 4820), ctx));
	}

	@Test void scheduledReferenceDoesNotBecomeABoundTool() {
		RequestContext empty = owner.withCaps(Vectors.empty());
		denied(engine.jobs().prepareTracked(ECHO, Maps.empty(), empty).start(), Abilities.INVOKE);
		denied(engine.jobs().prepareUntracked(ECHO, Maps.empty(), empty).start(), Abilities.INVOKE);
	}

	@Test void deferredArbitraryTargetStillRequiresInvoke() {
		var input = Maps.of("delay", 0, "operation", ECHO, "input", Maps.empty());
		Job job = engine.jobs().callOperation("v/test/ops/delay", input, owner.withCaps(Vectors.empty()));
		denied(job, Abilities.INVOKE);
	}

	@Test void boundToolsStillEnforceResourceCaveats() {
		var input = Maps.of("path", "w/result", "value", 42);
		var write = cap("w/", Capability.CRUD_WRITE);
		RequestContext deny = owner.withCaps(Vectors.of(write.assoc(Capability.NB,
			Maps.of("gate", "v/test/ops/denygate"))));
		denied(engine.jobs().startToolOperation(WRITE, input, deny).job(), Capability.CRUD_WRITE);
		RequestContext allow = owner.withCaps(Vectors.of(write.assoc(Capability.NB,
			Maps.of("gate", "v/test/ops/allowgate"))));
		assertNotNull(engine.jobs().runToolOperation(WRITE, input, allow).join());
	}

	@Test void admittedCallerCanUseBoundAgentEndpointWithoutInvoke() {
		engine.jobs().callOperation("v/ops/agent/create", Maps.of("agentId", "public-agent",
			"config", Maps.of("operation", "v/test/ops/taskcomplete", "accepts", "anyone")), owner)
			.awaitResult(5000);
		RequestContext asker = RequestContext.of(TestEngine.uniqueDID("admitted-asker")).withCaps(Vectors.empty());
		var input = Maps.of("agentId", owner.getUserDID() + "/g/public-agent", "input", Maps.of("task", "hello"));
		denied(engine.jobs().invokeOperation("v/ops/agent/request", input, asker), Abilities.INVOKE);
		assertNotNull(engine.jobs().callOperation("v/ops/agent/request", input, asker).awaitResult(5000));
	}
}

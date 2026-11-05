package covia.venue;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.api.Fields;

/**
 * What a capability gate is told about the invocation it rules on:
 * {@code {operation, input, caller, owner?}}, where {@code caller} is the
 * principal that acted and {@code owner} the namespace it acted in, present
 * only when that differs.
 */
class GateInputTest {

	private static final Engine engine = TestEngine.ENGINE;
	private static final String GATE = "v/ops/gate-input/record";
	private static final String ECHO = "v/test/ops/echo";

	/** A gate that passes and keeps every input it was given. */
	static final class Recorder extends AAdapter {
		final ConcurrentLinkedQueue<ACell> inputs = new ConcurrentLinkedQueue<>();

		@Override public String getName() { return "gate-input"; }
		@Override public String getDescription() { return "Records the input of each gate check"; }
		@Override protected void installAssets() {
			installAsset("gate-input/record", Maps.of(Fields.NAME, "Recording gate",
				Fields.OPERATION, Maps.of(Fields.ADAPTER, "gate-input:record")));
		}
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			inputs.add(input);
			return CompletableFuture.completedFuture(Maps.empty());
		}
	}

	private static synchronized Recorder recorder() {
		if (engine.getAdapter("gate-input") instanceof Recorder r) return r;
		Recorder r = new Recorder();
		engine.registerAdapter(r);
		return r;
	}

	/** The input of the one gate check whose invocation carried {@code marker}. */
	private static AMap<AString, ACell> checkOf(Recorder recorder, AString marker) {
		for (ACell input : recorder.inputs) {
			if (marker.equals(RT.getIn(input, Fields.INPUT, Fields.MESSAGE))) return RT.ensureMap(input);
		}
		return fail("no gate check carried " + marker);
	}

	private static RequestContext gated(RequestContext ctx) {
		return ctx.withCaps(Vectors.of(Capability.create(Strings.create(ECHO), Strings.create("invoke"),
			Maps.of("gate", Strings.create(GATE)))));
	}

	@Test void anAgentIsTheCallerInItsOwnersNamespace(TestInfo info) {
		Recorder recorder = recorder();
		AString owner = TestEngine.uniqueDID(info);
		RequestContext agent = gated(RequestContext.ofAgent(owner, Strings.create("gated")));
		AString marker = Strings.create("agent " + owner);
		engine.jobs().invokeOperation(ECHO, Maps.of(Fields.MESSAGE, marker), agent).awaitResult(10_000);

		AMap<AString, ACell> check = checkOf(recorder, marker);
		assertEquals(Strings.create(ECHO), check.get(Fields.OPERATION));
		assertEquals(agent.getCallerDID(), check.get(Fields.CALLER), "the gate is told who acted");
		assertEquals(owner, check.get(Fields.OWNER), "and whose namespace it acted in");
	}

	@Test void aUserActingInItsOwnNamespaceNamesNoOwner(TestInfo info) {
		Recorder recorder = recorder();
		AString user = TestEngine.uniqueDID(info);
		AString marker = Strings.create("user " + user);
		engine.jobs().invokeOperation(ECHO, Maps.of(Fields.MESSAGE, marker), gated(RequestContext.of(user)))
			.awaitResult(10_000);

		AMap<AString, ACell> check = checkOf(recorder, marker);
		assertEquals(user, check.get(Fields.CALLER));
		assertFalse(check.containsKey(Fields.OWNER));
	}
}

package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * End-to-end tests for the {@code scheduler} adapter ops, invoked through the
 * real resolve → adapter → engine-service path. Firing is exercised
 * deterministically via {@code scheduler:trigger}. See GRID_SCHEDULER.md.
 */
public class SchedulerAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final long HOUR = 3_600_000L;

	private static AString s(String x) { return Strings.create(x); }

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	private ACell invoke(String op, ACell input) throws Exception {
		return engine.jobs().invokeInternal(op, input, ctx).get(5, TimeUnit.SECONDS);
	}

	@Test
	public void testScheduleThenTrigger() throws Exception {
		ACell echoInput = Maps.of(s("hello"), s("world"));
		ACell scheduled = invoke("v/ops/scheduler/schedule", Maps.of(
			s("operation"), s("v/test/ops/echo"),
			s("input"), echoInput,
			s("after"), CVMLong.create(HOUR)));
		ACell handle = RT.getIn(scheduled, s("handle"));
		assertNotNull(handle, "schedule must return a handle");

		ACell triggered = invoke("v/ops/scheduler/trigger", Maps.of(s("handle"), handle));
		assertEquals(CVMBool.TRUE, RT.getIn(triggered, s("triggered")));
		assertEquals(echoInput, RT.getIn(triggered, s("result")),
			"trigger should return the fired echo op's result");
	}

	@Test
	public void testListThenCancel() throws Exception {
		ACell scheduled = invoke("v/ops/scheduler/schedule", Maps.of(
			s("operation"), s("v/test/ops/echo"),
			s("after"), CVMLong.create(HOUR)));
		ACell handle = RT.getIn(scheduled, s("handle"));

		ACell listed = invoke("v/ops/scheduler/list", Maps.empty());
		AVector<?> events = (AVector<?>) RT.getIn(listed, s("events"));
		assertEquals(1, events.count(), "list should show the one scheduled event");

		ACell cancelled = invoke("v/ops/scheduler/cancel", Maps.of(s("handle"), handle));
		assertEquals(CVMBool.TRUE, RT.getIn(cancelled, s("cancelled")));

		ACell listed2 = invoke("v/ops/scheduler/list", Maps.empty());
		assertEquals(0, ((AVector<?>) RT.getIn(listed2, s("events"))).count());
	}

	/** The handle round-trips as a hex string (the REST/JSON representation). */
	@Test
	public void testHandleAsHexString() throws Exception {
		ACell scheduled = invoke("v/ops/scheduler/schedule", Maps.of(
			s("operation"), s("v/test/ops/echo"),
			s("input"), s("ping"),
			s("after"), CVMLong.create(HOUR)));
		ACell handle = RT.getIn(scheduled, s("handle"));

		AString handleStr = Strings.create(handle.toString());   // "0x..."
		ACell triggered = invoke("v/ops/scheduler/trigger", Maps.of(s("handle"), handleStr));
		assertEquals(CVMBool.TRUE, RT.getIn(triggered, s("triggered")));
		assertEquals(s("ping"), RT.getIn(triggered, s("result")));
	}

	@Test
	public void testScheduleRequiresOperation() {
		var f = engine.jobs().invokeInternal("v/ops/scheduler/schedule",
			Maps.of(s("after"), CVMLong.create(HOUR)), ctx);
		assertThrows(java.util.concurrent.ExecutionException.class,
			() -> f.get(5, TimeUnit.SECONDS),
			"schedule without an 'operation' must fail");
	}
}

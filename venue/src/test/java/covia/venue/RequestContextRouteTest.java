package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;

/**
 * A request's route (RequestContext#getRoute): the steps that carried it,
 * each stamped with its adapter, bounded in length, and kept by the contexts
 * derived within the request but not by a principal acting as itself.
 */
public class RequestContextRouteTest {

	private static final AString ALICE = Strings.create("did:key:zAlice");
	private static final AString TOKEN = Strings.create("token");

	private static AMap<AString, ACell> step(int i) {
		return Maps.of(Fields.NAME, Strings.create("bot-" + i));
	}

	@Test
	public void aStepNamesTheAdapterThatAddedIt() {
		RequestContext direct = RequestContext.of(ALICE);
		assertNull(direct.getRoute(), "nothing carried a direct request");
		// The adapter's own claim to a name is replaced by the adapter that added the step
		RequestContext carried = direct.via(TOKEN, step(0).assoc(Fields.ADAPTER, Strings.create("forged")));
		assertEquals(Vectors.of(step(0).assoc(Fields.ADAPTER, TOKEN)), carried.getRoute());
		assertNull(direct.getRoute(), "a context is a value: adding a step makes a new one");
		assertEquals(2, carried.via(Strings.create("callable"), step(1)).getRoute().count());
		assertThrows(NullPointerException.class, () -> direct.via(null, step(0)));
		assertThrows(NullPointerException.class, () -> direct.via(TOKEN, null));
	}

	@Test
	public void aLongRouteKeepsWhereItEnteredAndItsLatestSteps() {
		RequestContext ctx = RequestContext.of(ALICE);
		for (int i = 0; i < RequestContext.ROUTE_MAX; i++) ctx = ctx.via(TOKEN, step(i));
		assertEquals(RequestContext.ROUTE_MAX, ctx.getRoute().count(), "up to the bound, every step is kept");
		for (int i = RequestContext.ROUTE_MAX; i < 13; i++) ctx = ctx.via(TOKEN, step(i));
		AVector<ACell> route = ctx.getRoute();
		assertEquals(RequestContext.ROUTE_MAX, route.count());
		assertEquals(step(0).assoc(Fields.ADAPTER, TOKEN), route.get(0));
		assertEquals(step(1).assoc(Fields.ADAPTER, TOKEN), route.get(1));
		long latest = RequestContext.ROUTE_MAX - RequestContext.ROUTE_HEAD - 1;
		assertEquals(Maps.of(RequestContext.ROUTE_ELIDED, CVMLong.create(13 - RequestContext.ROUTE_HEAD - latest)),
			route.get(RequestContext.ROUTE_HEAD), "the steps between fold into a count");
		assertEquals(step(12).assoc(Fields.ADAPTER, TOKEN), route.get(route.count() - 1));
		assertEquals(step(8).assoc(Fields.ADAPTER, TOKEN), route.get(RequestContext.ROUTE_HEAD + 1));
	}

	@Test
	public void theRouteStaysWithTheRequestButNotWithAPrincipalActingAsItself() {
		Engine engine = TestEngine.ENGINE;
		RequestContext carried = engine.context(ALICE).via(TOKEN, step(0));
		AVector<ACell> route = carried.getRoute();
		assertEquals(route, carried.withJobId(Blob.fromHex("01")).getRoute());
		assertEquals(route, carried.withSessionId(Blob.fromHex("02")).withTaskId(Blob.fromHex("03")).getRoute());
		assertEquals(route, carried.withCaps(Vectors.empty()).getRoute());
		assertEquals(route, carried.withProofs(Vectors.empty()).getRoute());
		assertEquals(route, carried.withAuthentication(Maps.empty()).getRoute(), "a record and a route travel together");
		RequestContext call = RequestContext.of(ALICE).calledFrom(carried.withAuthentication(Maps.empty()));
		assertEquals(route, call.getRoute(), "a call carries the request on");
		assertNull(call.getAuthentication(), "the invoker's authentication record stays with the invoker");
		assertNull(carried.actingAs(RequestContext.of(ALICE)).getRoute(), "a principal handed the run acts as itself");
	}
}

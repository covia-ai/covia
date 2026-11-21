package covia.venue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;

/**
 * A request's route (RequestContext#getRoute): where it started and each
 * handover, each record stamped with the adapter that wrote it and the
 * principal it vouches for, bounded in length, and kept by the contexts derived
 * within the request but not by a principal acting as itself.
 */
public class RequestContextRouteTest {

	private static final AString ALICE = Strings.create("did:key:zAlice");
	private static final AString TOKEN = Strings.create("token");

	private static AMap<AString, ACell> step(int i) {
		return Maps.of(Fields.NAME, Strings.create("bot-" + i));
	}

	/** {@code step} as the token adapter's record vouching for {@code principal}. */
	private static AMap<AString, ACell> stamped(AMap<AString, ACell> step, AString principal) {
		return step.assoc(Fields.VIA, TOKEN).assoc(Fields.PRINCIPAL, principal);
	}

	@Test
	public void aRequestStartsWhereItEntered() {
		AVector<ACell> direct = Vectors.of(Maps.of(RequestContext.ROUTE_DIRECT, CVMBool.TRUE, Fields.PRINCIPAL, ALICE));
		assertEquals(direct, RequestContext.of(ALICE).getRoute(), "a request an in-process caller makes is its principal's directly");
		assertEquals(direct, TestEngine.ENGINE.requestContext(ALICE, null).getRoute());
		AMap<AString, ACell> door = RT.ensureMap(TestEngine.ENGINE.requestContext(ALICE, null, Strings.create("http"), null, null)
			.getRoute().get(0));
		assertEquals(RequestContext.SIGN_IN, door.get(Fields.VIA), "a request a door authenticated starts where it signed in");
		assertEquals(ALICE, door.get(Fields.PRINCIPAL));
		assertNull(TestEngine.ENGINE.context(ALICE).getRoute(), "a context the venue makes for its own work has none");
	}

	@Test
	public void ingressFactsDoNotChangeAuthority() {
		Engine engine = TestEngine.ENGINE;
		RequestContext local = engine.requestContext(ALICE, null);
		RequestContext ingress = engine.requestContext(ALICE, null, Strings.create("http"),
			Strings.create("a2a"), Strings.create("venueJwt"));
		assertEquals(local.getAuthorityDID(), ingress.getAuthorityDID());
		assertEquals(local.getOrigin(), ingress.getOrigin());
		assertEquals(local.getCaps(), ingress.getCaps());
		assertEquals(local.getProofs(), ingress.getProofs());
		assertEquals(1, ingress.getRoute().count());
		assertEquals(Strings.create("http"), RT.getIn(ingress.getRoute(), 0, Fields.TRANSPORT));
		assertEquals(Strings.create("a2a"), RT.getIn(ingress.getRoute(), 0, Fields.PROTOCOL));
		assertEquals(Strings.create("venueJwt"), RT.getIn(ingress.getRoute(), 0, Fields.CREDENTIAL_TYPE));
		RequestContext unknown = engine.requestContext(ALICE, null, Strings.create("http"), null, null);
		AMap<AString, ACell> record = RT.ensureMap(unknown.getRoute().get(0));
		assertFalse(record.containsKey(Fields.CREDENTIAL_TYPE));
		assertFalse(record.containsKey(Fields.PROTOCOL));
		assertNull(RT.getIn(local.getRoute(), 0, Fields.TRANSPORT));
	}

	@Test
	public void aRecordNamesTheAdapterThatAddedItAndWhomItVouchesFor() {
		RequestContext unstamped = TestEngine.ENGINE.context(ALICE);
		// The adapter's own claim to a name or a principal is replaced by the venue's stamp
		RequestContext carried = unstamped.via(TOKEN, step(0).assoc(Fields.VIA, Strings.create("forged"))
			.assoc(Fields.PRINCIPAL, Strings.create("did:key:zMallory")).assoc(Fields.ADAPTER, Strings.create("forged")));
		assertEquals(Vectors.of(stamped(step(0), ALICE)), carried.getRoute());
		assertNull(unstamped.getRoute(), "a context is a value: adding a record makes a new one");
		assertEquals(2, carried.via(Strings.create("callable"), step(1)).getRoute().count());
		RouteText text = RouteText.of("hello");
		assertEquals(text.parts(), RT.getIn(unstamped.via(TOKEN, step(0), text).getRoute(), 0, Fields.TEXT),
			"with the adapter's own text");
		assertThrows(NullPointerException.class, () -> unstamped.via(null, step(0)));
		assertThrows(NullPointerException.class, () -> unstamped.via(TOKEN, null));
	}

	@Test
	public void aLongRouteKeepsWhereItStartedAndItsLatestRecords() {
		RequestContext ctx = TestEngine.ENGINE.context(ALICE);
		for (int i = 0; i < RequestContext.ROUTE_MAX; i++) ctx = ctx.via(TOKEN, step(i));
		assertEquals(RequestContext.ROUTE_MAX, ctx.getRoute().count(), "up to the bound, every record is kept");
		for (int i = RequestContext.ROUTE_MAX; i < 13; i++) ctx = ctx.via(TOKEN, step(i));
		AVector<ACell> route = ctx.getRoute();
		assertEquals(RequestContext.ROUTE_MAX, route.count());
		assertEquals(stamped(step(0), ALICE), route.get(0));
		assertEquals(stamped(step(1), ALICE), route.get(1));
		long latest = RequestContext.ROUTE_MAX - RequestContext.ROUTE_HEAD - 1;
		assertEquals(Maps.of(RequestContext.ROUTE_ELIDED, CVMLong.create(13 - RequestContext.ROUTE_HEAD - latest)),
			route.get(RequestContext.ROUTE_HEAD), "the records between fold into a count");
		assertEquals(stamped(step(12), ALICE), route.get(route.count() - 1));
		assertEquals(stamped(step(8), ALICE), route.get(RequestContext.ROUTE_HEAD + 1));
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

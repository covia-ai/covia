package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.agent.ContextAssembler.Spec;
import covia.api.Fields;
import covia.exception.FaultException;
import covia.grid.Authority;
import covia.venue.Engine;
import covia.venue.Provenance;
import covia.venue.RequestContext;
import covia.venue.RouteText;
import covia.venue.TestLogs;

/**
 * An adapter as the source of a message (AUTHORITY.md, Provenance): its
 * principal and sub-principals, what its own messages read as, and its
 * wording for its records at display time, with the stored text in its place
 * while it is not loaded or when its wording fails.
 *
 * <p>Isolated because one test provokes the ERROR an adapter's failing wording
 * logs, silenced through the process-wide {@code FaultException} logger.</p>
 */
@Isolated("silences the shared FaultException logger")
public class AdapterSourceTest {

	private static final AString WORDED = Strings.intern("worded");
	private static final AString FAIL = Strings.intern("fail");

	/** An adapter that keeps rooms, and words a record itself when the record asks it to. */
	private static class Rooms extends AAdapter {
		@Override public String getName() { return "rooms"; }
		@Override public String getDescription() { return "Rooms for the adapter source test"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
		@Override public RouteText routeText(AMap<AString, ACell> record, Provenance.Reader reader) {
			if (record.containsKey(FAIL)) throw new IllegalStateException("broken wording");
			if (!record.containsKey(WORDED)) return null;
			return RouteText.builder().text(reader.isAgent() ? "worded for an agent" : "worded for a person").build();
		}
	}

	private static Engine engine;
	private static Rooms rooms;
	private static final AString ALICE = Strings.create("did:key:z6MkAdapterSourceAlice");
	private static final AString BOB = Strings.create("did:key:z6MkAdapterSourceBob");
	/** Alice's agent "helper", whose turns are rendered. */
	private static final RequestContext HELPER = RequestContext.ofAuthority(Authority.ofAgent(ALICE, Strings.create("helper")));

	@BeforeAll static void start() {
		engine = Engine.createTemp(Maps.empty());
		rooms = new Rooms();
		engine.registerAdapter(rooms);
	}

	@AfterAll static void stop() { engine.close(); }

	private static AMap<AString, ACell> turn(RequestContext from) {
		return Maps.of(Fields.CALLER, from.getAuthorityDID(), Fields.ROUTE, from.getRoute(), Fields.MESSAGE, Strings.create("a post"));
	}

	private static String bracket(AMap<AString, ACell> turn) {
		return TurnProvenance.element(Labels.BRACKET, engine, HELPER, turn);
	}

	private static String forAlice(AMap<AString, ACell> turn) {
		return Provenance.of(turn).text(Provenance.Reader.person(engine, ALICE));
	}

	private static java.util.List<String> elements(ACell... turns) {
		Spec spec = new Spec(engine, HELPER, null, null, null, null, 0, null,
			null, null, null, null, null, Vectors.of((Object[]) turns), true, null, null, null, null, null);
		AVector<ACell> messages = ContextAssembler.assemble(spec).messages();
		java.util.List<String> out = new java.util.ArrayList<>();
		for (long i = 0; i < messages.count(); i++) {
			AString content = RT.ensureString(RT.getIn(messages.get(i), "content"));
			if (content != null && content.toString().startsWith("[From")) out.add(content.toString());
		}
		return out;
	}

	@Test
	public void anAdapterAndItsSubPrincipalsAreItsPaths() {
		String venue = engine.getDIDString().toString();
		assertEquals(Strings.create(venue + ":x:rooms"), rooms.principal());
		assertEquals(Strings.create(venue + ":x:rooms:design-review"), rooms.principal("design-review"));
		assertEquals(Strings.create(venue + ":x:rooms:floor-2:design-review"), rooms.principal("floor-2/design-review"));
		assertEquals("x/rooms", AAdapter.path(engine, rooms.principal()));
		assertEquals("x/rooms/floor-2/design-review", AAdapter.path(engine, rooms.principal("floor-2/design-review")));
		for (String path : new String[] {"", "a//b", "/a", "g", "a/g/b", "a b", "a:b", null}) {
			IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> rooms.principal(path));
			assertEquals(AAdapter.INVALID_SUB_PRINCIPAL.formatted(path, "rooms"), refused.getMessage());
		}
		assertNull(AAdapter.path(engine, Strings.create("did:key:z6MkSomeoneElse:x:rooms")), "only the venue's DID has adapter principals");
		assertNull(AAdapter.path(engine, ALICE));
		assertNull(AAdapter.path(engine, Strings.create(venue + ":x:rooms:g:helper")), "an agent's shape is not an adapter's");
	}

	@Test
	public void anAdapterPrincipalHoldsNothing() {
		assertEquals(Vectors.empty(), engine.context(rooms.principal()).getCaps());
		assertEquals(Vectors.empty(), engine.context(rooms.principal("design-review")).getCaps());
		assertEquals(Vectors.empty(), rooms.sourceContext("design-review", null, null).getCaps());
	}

	@Test
	public void anAdaptersOwnMessageNamesTheAdapterOnce() {
		RequestContext room = rooms.sourceContext("design-review", Maps.of("room", "design-review"),
			RouteText.builder().text("posted by ").who(BOB).build());
		assertEquals(rooms.principal("design-review"), room.getAuthorityDID());
		assertEquals(Strings.create("rooms"), RT.getIn(room.getRoute(), 0, Fields.VIA));
		assertEquals(rooms.principal("design-review"), RT.getIn(room.getRoute(), 0, Fields.PRINCIPAL));
		assertEquals("[From adapter x/rooms/design-review (posted by user " + BOB + ")]", bracket(turn(room)));
		assertEquals("adapter x/rooms/design-review (posted by user " + BOB + ")", forAlice(turn(room)));
		assertEquals("[From adapter x/rooms]", bracket(turn(rooms.sourceContext(null, null, null))),
			"an adapter that adds nothing is named alone");
		assertEquals("[From user " + BOB + " via rooms (\"design-review\")]",
			bracket(Maps.of(Fields.CALLER, BOB, Fields.ROUTE, Vectors.of(RequestContext.routeRecord(Strings.create("rooms"),
				BOB, Maps.of(Fields.NAME, "design-review"), null)))),
			"a record vouching for anyone else names them and the adapter, as before");
	}

	@Test
	public void aPostByTheOwnerIsNotTheOwnersMessage() {
		AMap<AString, ACell> post = turn(rooms.sourceContext("design-review", null,
			RouteText.builder().text("posted by ").who(ALICE).build()));
		assertEquals("[From adapter x/rooms/design-review (posted by your owner)]", bracket(post));
		assertFalse(TurnProvenance.ownersAlone(engine, HELPER, TurnProvenance.key(post)));
		assertEquals(java.util.List.of(bracket(post), bracket(post)), elements(post, post),
			"labelled every time, as anyone but the owner is");
	}

	@Test
	public void theAdapterWordsItsRecordsForEachReader() {
		AMap<AString, ACell> post = turn(rooms.sourceContext("design-review", Maps.of(WORDED, CVMBool.TRUE), RouteText.of("stored words")));
		assertEquals("[From adapter x/rooms/design-review (worded for an agent)]", bracket(post));
		assertEquals("adapter x/rooms/design-review (worded for a person)", forAlice(post));
	}

	@Test
	public void theStoredTextShowsWhileTheAdapterIsNotLoaded() {
		AString absent = Strings.create(engine.getDIDString() + ":x:absent");
		AMap<AString, ACell> post = Maps.of(Fields.CALLER, absent, Fields.ROUTE, Vectors.of(RequestContext.routeRecord(
			Strings.create("absent"), absent, Maps.of(WORDED, CVMBool.TRUE), RouteText.of("stored words"))));
		assertEquals("[From adapter x/absent (stored words)]", bracket(post));
	}

	@Test
	public void aFailingWordingIsTheAdaptersFaultAndTheStoredTextShows() throws Exception {
		AMap<AString, ACell> post = turn(rooms.sourceContext(null, Maps.of(FAIL, CVMBool.TRUE), RouteText.of("stored words")));
		String[] shown = new String[1];
		TestLogs.quiet(FaultException.class, () -> shown[0] = bracket(post));
		assertEquals("[From adapter x/rooms (stored words)]", shown[0]);
	}
}

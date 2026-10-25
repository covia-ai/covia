package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.auth.ucan.Capability;
import convex.auth.ucan.UCAN;
import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.lattice.cursor.AForkableCursor;
import convex.lattice.cursor.Cursors;
import covia.api.Fields;
import covia.adapter.agent.ContextLoader;
import covia.exception.AuthException;
import covia.lattice.WorkspaceRoot;
import covia.venue.Engine;
import covia.venue.RequestContext;

class ExtensionRouteTest {
	private Engine engine;
	private CoviaAdapter covia;
	private Probe probe;
	private AKeyPair aliceKey;
	private AString aliceDID, bobDID;
	private RequestContext alice, bob;

	private static class Probe extends AAdapter {
		boolean writable = true;
		boolean projection;
		int resolutions;
		RequestContext received;
		@Override public String getName() { return "extension-test"; }
		@Override public String getDescription() { return "Extension route test"; }
		@Override public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
			return CompletableFuture.completedFuture(input);
		}
		@Override public AForkableCursor<ACell> latticeView(RequestContext ctx) {
			resolutions++;
			received = ctx;
			if (projection) return Cursors.root(Vectors.of(ctx.getUserDID(), "projected"));
			return adapterState(ctx.getUserDID()).cursor().withDefault(Maps.empty())
				.path(Strings.create("accounts")).withDefault(Maps.empty()).path(ctx.getUserDID());
		}
		@Override public boolean canWriteLattice(RequestContext ctx, ACell[] path) {
			return writable && !projection && (path.length == 0 || !Strings.create("fixed").equals(path[0]));
		}
	}

	@BeforeEach void setup() {
		engine = Engine.createTemp(Maps.empty());
		covia = new CoviaAdapter();
		engine.registerAdapter(covia);
		probe = new Probe();
		engine.registerAdapter(probe);
		aliceKey = AKeyPair.generate();
		aliceDID = UCAN.toDIDKey(aliceKey.getAccountKey());
		bobDID = UCAN.toDIDKey(AKeyPair.generate().getAccountKey());
		alice = RequestContext.of(aliceDID);
		bob = RequestContext.of(bobDID);
		engine.getVenueState().users().ensure(aliceDID);
		engine.getVenueState().users().ensure(bobDID);
		engine.adapterState(probe.getName()).set(Maps.of("private", "never exposed", "accounts",
			Maps.of(aliceDID, Maps.of("label", "alice", "items", Vectors.of(1L, 2L), "fixed", "read only"),
				bobDID, Maps.of("label", "bob"))));
	}

	@AfterEach void close() { engine.close(); }

	private ACell run(String op, ACell input, RequestContext ctx) {
		return covia.invokeFuture(ctx, Maps.of(Fields.OPERATION, Maps.of(Fields.ADAPTER, "covia:" + op)), input).join();
	}
	private ACell read(String path, RequestContext ctx) {
		return RT.getIn(run("read", Maps.of(Fields.PATH, path), ctx), Fields.VALUE);
	}
	private static String path(String suffix) { return "x/extension-test" + suffix; }
	private void write(String path, ACell value, RequestContext ctx) {
		run("write", Maps.of(Fields.PATH, path, Fields.VALUE, value), ctx);
	}

	@Test void viewsArePerUserAndNeverExposeThePrivateAdapterRoot() {
		ACell before = engine.getVenueState().get();
		assertEquals(Strings.create("alice"), read(path("/label"), alice));
		assertEquals(Strings.create("bob"), read(path("/label"), bob));
		assertNull(read(path("/private"), alice));
		assertNull(read(path("/accounts/" + bobDID), alice));
		assertEquals(Strings.create("alice"), engine.resolvePath(Strings.create("/" + path("/label")), alice));
		assertEquals(Strings.create("alice"), read(aliceDID + "/" + path("/label"), alice));
		assertEquals("alice", new ContextLoader(engine).resolveValue(Strings.create(path("/label")), alice).content());
		assertNull(engine.getVenueState().users().get(aliceDID).workspaceRoot().read(WorkspaceRoot.parseStringPath(path(""))));
		assertEquals(before, engine.getVenueState().get(), "reading a view does not materialise user storage");
	}

	@Test void normalAccessorsUseTheConnectedViewAndTypedPaths() {
		write(path("/note"), Strings.create("hello"), alice);
		run("append", Maps.of(Fields.PATH, path("/items"), Fields.VALUE, 3L), alice);
		assertEquals(CVMLong.create(3), read(path("/items/2"), alice));
		assertEquals(CVMLong.create(3), RT.getIn(run("read", Maps.of(Fields.PATH,
			Vectors.of("x", "extension-test", "items", 2L)), alice), Fields.VALUE));
		assertEquals(CVMLong.create(4), RT.getIn(run("list", Maps.of(Fields.PATH, path("")), alice), "count"));
		assertNotNull(run("slice", Maps.of(Fields.PATH, path("/items"), "limit", 2L), alice));
		assertNotNull(run("inspect", Maps.of("paths", Vectors.of(path("/note"))), alice));
		run("copy", Maps.of("from", path("/note"), "to", "w/copied"), alice);
		assertEquals(Strings.create("hello"), read("w/copied", alice));
		assertEquals(CVMBool.TRUE, RT.getIn(run("delete", Maps.of(Fields.PATH, path("/note")), alice), "deleted"));
		assertNull(read(path("/note"), alice));
		assertEquals(Vectors.of(1L, 2L, 3L), engine.adapterState(probe.getName()).read("accounts/" + aliceDID + "/items"));
	}

	@Test void writesNeedBothAdapterPermissionAndExactCrudAuthority() {
		RequestContext onlyItems = alice.withCaps(Vectors.of(Capability.create(Strings.create(path("/items")), Capability.CRUD_WRITE)));
		run("append", Maps.of(Fields.PATH, Vectors.of("x", "extension-test", "items"), Fields.VALUE, 3L), onlyItems);
		int before = probe.resolutions;
		assertThrows(CompletionException.class, () -> write(path("/label"), Strings.create("no"), onlyItems));
		assertEquals(before, probe.resolutions, "denied scope must not enter the provider hook");
		assertThrows(AuthException.class, () -> engine.resolvePath(Strings.create(path("/items")), onlyItems));
		assertThrows(CompletionException.class, () -> write(path("/fixed"), Strings.create("no"), alice));
		probe.writable = false;
		assertEquals(Strings.create("alice"), read(path("/label"), alice));
		for (String op : new String[] {"write", "append", "delete"}) {
			assertThrows(CompletionException.class, () -> run(op, Maps.of(Fields.PATH, path("/label"), Fields.VALUE, "no"), alice));
		}
	}

	@Test void didQualifiedDelegationRetainsActorAndSelectsTheOwnersView() {
		String resource = aliceDID + "/" + path("");
		assertThrows(CompletionException.class, () -> read(resource + "/label", bob));
		assertThrows(AuthException.class, () -> engine.resolvePath(Strings.create(resource + "/label"), bob));
		UCAN grant = UCAN.create(aliceKey, UCAN.fromDIDKey(bobDID), System.currentTimeMillis() / 1000 + 300,
			Vectors.of(Capability.create(Strings.create(resource), Strings.create("crud"))), Vectors.empty());
		RequestContext delegated = bob.withProofs(Vectors.of(grant.toMap()));
		assertEquals(Strings.create("alice"), read(resource + "/label", delegated));
		assertEquals(aliceDID, probe.received.getUserDID());
		assertEquals(bobDID, probe.received.getCallerDID());
		write("/" + resource + "/label", Strings.create("delegated"), delegated);
		run("append", Maps.of(Fields.PATH, resource + "/items", Fields.VALUE, 3L), delegated);
		assertEquals(Strings.create("delegated"), read(path("/label"), alice));
		assertEquals(Strings.create("bob"), read(path("/label"), bob));
		run("delete", Maps.of(Fields.PATH, resource + "/label"), delegated);
		assertNull(read(path("/label"), alice));
		assertNotNull(run("inspect", Maps.of("paths", Vectors.of(resource + "/items")), delegated));
	}

	@Test void absentDisabledAndUnloadedRoutesNeverFallThroughToUserStorage() {
		engine.getVenueState().users().get(aliceDID).workspaceRoot().write(
			WorkspaceRoot.parseStringPath(path("/label")), Strings.create("shadow"));
		var retained = covia.resolveVirtual(alice, WorkspaceRoot.parseStringPath(path("/label")), Capability.CRUD_WRITE);
		engine.disableAdapter(probe.getName());
		assertNull(read(path("/label"), alice));
		assertThrows(CompletionException.class, () -> write(path("/label"), Strings.create("no"), alice));
		assertThrows(AuthException.class, () -> retained.cursor().write(retained.remainingKeys(), Strings.create("late")));
		engine.enableAdapter(probe.getName());
		assertEquals(Strings.create("alice"), read(path("/label"), alice));
		engine.removeAdapter(probe.getName());
		assertNull(read(path("/label"), alice));
		assertNull(read("x/covia", alice), "default adapters expose nothing");
		assertNull(read("x", alice));
	}

	@Test void projectedValuesAreReadableButNotImplicitlyWritable() {
		probe.projection = true;
		assertEquals(Vectors.of(aliceDID, "projected"), read(path(""), alice));
		assertEquals(Strings.create("projected"), read(path("/1"), alice));
		assertThrows(CompletionException.class, () -> write(path("/1"), Strings.create("no"), alice));
		var view = covia.resolveVirtual(alice, WorkspaceRoot.parseStringPath(path("")));
		assertThrows(AuthException.class, () -> view.cursor().cursor().set(Maps.empty()));
	}

	@Test void arbitraryRootValuesAndConcurrentUpdatesUseTheVenueAtomicState() {
		write(path(""), Vectors.empty(), alice);
		CompletableFuture<?>[] writes = new CompletableFuture<?>[20];
		for (int i = 0; i < writes.length; i++) {
			long value = i;
			writes[i] = CompletableFuture.runAsync(() -> run("append", Maps.of(
				Fields.PATH, path(""), Fields.VALUE, value), alice));
		}
		CompletableFuture.allOf(writes).join();
		var result = (convex.core.data.AVector<?>) read(path(""), alice);
		assertEquals(20, result.count());
		write(path("/0"), CVMLong.create(99), alice);
		assertEquals(CVMLong.create(99), read(path("/0"), alice));
		var target = covia.resolveVirtual(alice, WorkspaceRoot.parseStringPath(path("/0")), Capability.CRUD_WRITE);
		ACell before = engine.getVenueState().get();
		assertThrows(IllegalArgumentException.class, () -> target.cursor().transact(draft -> {
			draft.write(target.remainingKeys(), CVMLong.ZERO);
			throw new IllegalArgumentException("test rollback");
		}));
		assertEquals(before, engine.getVenueState().get());
	}

	@Test void malformedPathsAreRejectedBeforeAdapterResolution() {
		for (ACell path : new ACell[] { Strings.create(path("/../private")),
				Vectors.of("x", "extension-test", "items/0"), Vectors.of("x", "extension-test", -1L) }) {
			int before = probe.resolutions;
			assertThrows(CompletionException.class, () -> run("read", Maps.of(Fields.PATH, path), alice));
			assertEquals(before, probe.resolutions);
		}
	}
}

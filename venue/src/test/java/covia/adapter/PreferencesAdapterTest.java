package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.auth.ucan.Capability;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.agent.ContextLoader;
import covia.api.Fields;
import covia.exception.ConfigurationException;
import covia.exception.InvalidInputException;
import covia.exception.JobFailedException;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import covia.venue.User;

/**
 * The preferences adapter: each command on maps and lists, the path and value
 * rules, the size limit, authority, concurrent edits, the read-only
 * {@code x/preferences} view, the pin, account deletion and configuration.
 * Uses the shared {@link TestEngine#ENGINE} with a fresh user per test; the
 * size-limit tests configure a venue of their own.
 */
public class PreferencesAdapterTest {

	private static final String OP = "v/ops/preferences";
	private final Engine engine = TestEngine.ENGINE;
	private RequestContext alice;

	@BeforeEach
	public void setup(TestInfo info) {
		alice = RequestContext.of(TestEngine.uniqueDID(info));
	}

	private ACell edit(RequestContext ctx, Object... input) {
		return engine.jobs().invokeOperation(OP, Maps.of(input), ctx).awaitResult(5000);
	}

	private ACell edit(Object... input) {
		return edit(alice, input);
	}

	private JobFailedException refused(Object... input) {
		return assertThrows(JobFailedException.class, () -> edit(input));
	}

	/** The caller's preferences as read at x/preferences. */
	private ACell prefs(RequestContext ctx) {
		ACell read = engine.jobs().invokeOperation("v/ops/covia/read",
			Maps.of(Fields.PATH, "x/preferences"), ctx).awaitResult(5000);
		return RT.getIn(read, Fields.VALUE);
	}

	private ACell prefs() {
		return prefs(alice);
	}

	private static final AMap<AString, ACell> OK = Maps.of("ok", CVMBool.TRUE);

	// ========== set ==========

	@Test
	public void testSetStoresAndCreatesMapsOnTheWay() {
		assertEquals(OK, edit("command", "set", "path", "units", "value", "metric"));
		edit("command", "set", "path", "diet/vegetarian", "value", true);
		assertEquals(Maps.of("units", "metric", "diet", Maps.of("vegetarian", true)), prefs());
	}

	@Test
	public void testSetReplacesAListItemByPosition() {
		edit("command", "set", "path", "allergies", "value", Vectors.of("nuts", "dairy"));
		edit("command", "set", "path", "allergies/1", "value", "shellfish");
		assertEquals(Vectors.of("nuts", "shellfish"), RT.getIn(prefs(), "allergies"));
		refused("command", "set", "path", "allergies/2", "value", "eggs");
		refused("command", "set", "path", "allergies/first", "value", "eggs");
	}

	@Test
	public void testSetNullRemoves() {
		edit("command", "set", "path", "tone", "value", "brief");
		edit("command", "set", "path", "tone", "value", null);
		assertNull(RT.getIn(prefs(), "tone"));
	}

	@Test
	public void testCannotGoInsideAValue() {
		edit("command", "set", "path", "units", "value", "metric");
		refused("command", "set", "path", "units/length", "value", "km");
		assertEquals(Maps.of("units", "metric"), prefs());
	}

	// ========== remove ==========

	@Test
	public void testRemoveKeyAndReportNothingRemoved() {
		edit("command", "set", "path", "tone", "value", "brief");
		assertEquals(OK, edit("command", "remove", "path", "tone"));
		assertEquals(Maps.of("ok", CVMBool.TRUE, "removed", CVMBool.FALSE), edit("command", "remove", "path", "tone"));
		assertEquals(Maps.of("ok", CVMBool.TRUE, "removed", CVMBool.FALSE), edit("command", "remove", "path", "nothing/here"));
	}

	@Test
	public void testRemoveListItemMovesLaterItemsUp() {
		edit("command", "set", "path", "allergies", "value", Vectors.of("nuts", "dairy", "eggs"));
		edit("command", "remove", "path", "allergies/0");
		assertEquals(Vectors.of("dairy", "eggs"), RT.getIn(prefs(), "allergies"));
	}

	@Test
	public void testRemoveByValue() {
		edit("command", "set", "path", "allergies", "value", Vectors.of("nuts", "dairy", "nuts"));
		assertEquals(OK, edit("command", "remove", "path", "allergies", "value", "nuts"));
		assertEquals(Vectors.of("dairy"), RT.getIn(prefs(), "allergies"));
		assertEquals(Maps.of("ok", CVMBool.TRUE, "removed", CVMBool.FALSE),
			edit("command", "remove", "path", "allergies", "value", "eggs"));
		edit("command", "set", "path", "tone", "value", "brief");
		refused("command", "remove", "path", "tone", "value", "brief");
	}

	// ========== append and insert ==========

	@Test
	public void testAppendCreatesAndExtendsAList() {
		edit("command", "append", "path", "diet/allergies", "value", "nuts");
		edit("command", "append", "path", "diet/allergies", "value", "dairy");
		assertEquals(Vectors.of("nuts", "dairy"), RT.getIn(prefs(), "diet", "allergies"));
		refused("command", "append", "path", "diet", "value", "x");
	}

	@Test
	public void testInsertBeforePositionOrAtTheEnd() {
		edit("command", "set", "path", "steps", "value", Vectors.of("b", "c"));
		edit("command", "insert", "path", "steps/0", "value", "a");
		edit("command", "insert", "path", "steps/3", "value", "d");
		assertEquals(Vectors.of("a", "b", "c", "d"), RT.getIn(prefs(), "steps"));
		refused("command", "insert", "path", "steps/9", "value", "z");
		refused("command", "insert", "path", "missing/0", "value", "z");
		edit("command", "set", "path", "tone", "value", "brief");
		refused("command", "insert", "path", "tone/0", "value", "z");
	}

	// ========== merge ==========

	@Test
	public void testMergeWithoutPathMergesIntoAllPreferences() {
		edit("command", "merge", "value", Maps.of("units", "metric", "diet", Maps.of("vegetarian", true)));
		edit("command", "merge", "value", Maps.of("tone", "brief", "diet", Maps.of("vegan", false)));
		assertEquals(Maps.of("units", "metric", "tone", "brief",
			"diet", Maps.of("vegetarian", true, "vegan", false)), prefs());
	}

	@Test
	public void testMergeNullRemovesAndListsAreReplaced() {
		edit("command", "merge", "value", Maps.of("tone", "brief", "allergies", Vectors.of("nuts", "dairy")));
		edit("command", "merge", "value", Maps.of("tone", null, "allergies", Vectors.of("eggs")));
		assertEquals(Maps.of("allergies", Vectors.of("eggs")), prefs(), "RFC 7396: a list replaces the list");
	}

	@Test
	public void testMergeAtAPathAndItsRefusals() {
		edit("command", "merge", "path", "diet", "value", Maps.of("vegetarian", true));
		assertEquals(Maps.of("diet", Maps.of("vegetarian", true)), prefs());
		refused("command", "merge", "value", "metric");
		edit("command", "set", "path", "allergies", "value", Vectors.of("nuts"));
		refused("command", "merge", "path", "allergies", "value", Maps.of("x", 1));
	}

	// ========== paths and values ==========

	@Test
	public void testEveryCommandButMergeNeedsAPath() {
		for (String command : new String[] {"set", "remove", "append", "insert"}) {
			JobFailedException e = refused("command", command, "value", "x");
			assertTrue(e.getMessage().contains(PreferencesAdapter.PATH_REQUIRED.formatted(command)), e.getMessage());
		}
		refused("command", "set", "path", "", "value", "x");
		refused("command", "frobnicate", "path", "units", "value", "x");
		assertNull(prefs(), "nothing was written");
	}

	@Test
	public void testAPathMayNameTheViewItIsReadAt() {
		edit("command", "set", "path", "x/preferences/units", "value", "metric");
		edit("command", "set", "path", "/tone/", "value", "brief");
		assertEquals(Maps.of("units", "metric", "tone", "brief"), prefs());
		refused("command", "set", "path", "diet//vegan", "value", true);
	}

	@Test
	public void testValuesHoldNoNullsAndOnlyAddressableKeys() {
		refused("command", "set", "path", "diet", "value", Maps.of("vegan", null));
		refused("command", "append", "path", "list", "value", Vectors.of("a", null));
		refused("command", "set", "path", "diet", "value", Maps.of("a/b", true));
		refused("command", "merge", "value", Maps.of("", 1));
		refused("command", "set", "path", "tone");
		assertNull(prefs(), "nothing was written");
	}

	// ========== authority ==========

	@Test
	public void testEditsNeedWriteAuthorityOnTheirPath() {
		AMap<AString, ACell> invoke = Capability.create(Strings.create(OP), AAdapter.INVOKE);
		AMap<AString, ACell> read = Capability.create(Strings.create("x/preferences"), Capability.CRUD_READ);
		AMap<AString, ACell> tone = Capability.create(Strings.create("x/preferences/tone"), Capability.CRUD_WRITE);
		RequestContext reader = alice.withCaps(Vectors.of(invoke, read));
		RequestContext toneOnly = alice.withCaps(Vectors.of(invoke, tone));

		assertThrows(JobFailedException.class, () -> edit(reader, "command", "set", "path", "tone", "value", "brief"));
		edit(toneOnly, "command", "set", "path", "tone", "value", "brief");
		assertThrows(JobFailedException.class, () -> edit(toneOnly, "command", "set", "path", "units", "value", "metric"));
		assertThrows(JobFailedException.class, () -> edit(toneOnly, "command", "merge", "value", Maps.of("tone", "long")));
		assertEquals(Maps.of("tone", "brief"), prefs());
	}

	@Test
	public void testViewIsReadOnlyToCoviaWrites() {
		edit("command", "set", "path", "units", "value", "metric");
		assertThrows(JobFailedException.class, () -> engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, "x/preferences/units", Fields.VALUE, "imperial"), alice).awaitResult(5000));
		assertEquals(Maps.of("units", "metric"), prefs());
	}

	@Test
	public void testEachUserHasTheirOwn() {
		RequestContext bob = RequestContext.of(Strings.create(alice.getUserDID() + "-bob"));
		edit("command", "set", "path", "units", "value", "metric");
		assertNull(prefs(bob));
	}

	// ========== agents and concurrency ==========

	@Test
	public void testAgentEditsItsOwnersPreferences() {
		User user = engine.getVenueState().users().ensure(alice.getUserDID());
		user.ensureAgent("prefs-agent", Maps.empty(), null);
		RequestContext agent = RequestContext.ofAgent(alice.getUserDID(), Strings.create("prefs-agent"));
		edit(agent, "command", "set", "path", "units", "value", "metric");
		assertEquals(Maps.of("units", "metric"), prefs());
	}

	@Test
	public void testConcurrentEditsByUserAndAgentAllApply() throws Exception {
		User user = engine.getVenueState().users().ensure(alice.getUserDID());
		user.ensureAgent("prefs-sharer", Maps.empty(), null);
		RequestContext agent = RequestContext.ofAgent(alice.getUserDID(), Strings.create("prefs-sharer"));
		int n = 16;
		CountDownLatch go = new CountDownLatch(1);
		List<Future<ACell>> futures = new ArrayList<>();
		try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < n; i++) {
				RequestContext caller = (i % 2 == 0) ? alice : agent;
				String item = "item " + i;
				futures.add(pool.submit(() -> {
					go.await();
					return edit(caller, "command", "append", "path", "items", "value", item);
				}));
			}
			go.countDown();
			for (Future<ACell> f : futures) f.get(10, TimeUnit.SECONDS);
		}
		AVector<ACell> items = RT.ensureVector(RT.getIn(prefs(), "items"));
		assertEquals(n, items.count(), "none lost");
		for (int i = 0; i < n; i++) assertTrue(items.contains(Strings.create("item " + i)), "kept: item " + i);
	}

	// ========== pin, deletion and state ==========

	@Test
	public void testPinShowsPreferencesAsData() {
		edit("command", "set", "path", "units", "value", "metric");
		ACell entry = Maps.of("ref", "x/preferences", "label", "User preferences", "volatile", true, "trusted", false);
		ContextLoader.Resolved resolved = new ContextLoader(engine).resolveValue(entry, alice);
		assertNotNull(resolved);
		assertTrue(resolved.content().contains("metric"), resolved.content());
	}

	@Test
	public void testAccountDeletionRemovesPreferences() {
		edit("command", "set", "path", "units", "value", "metric");
		AAdapter adapter = engine.getAdapter("preferences");
		adapter.deleteUserState(alice.getUserDID(), engine.adapterState("preferences"));
		assertNull(prefs());
	}

	@Test
	public void testStateMigrationAtVersionZero() {
		AAdapter adapter = engine.getAdapter("preferences");
		ACell state = Maps.of("users", Maps.of("did:key:z6MkExample", Maps.of("units", "metric")));
		assertNull(adapter.checkStateMigration(List.of(state)));
	}

	// ========== size limit (a venue of its own: it changes adapter configuration) ==========

	@Test
	public void testSizeLimitDefaultsOverridesAndShrinking() {
		String did = alice.getUserDID().toString();
		Engine venue = Engine.createTemp(Maps.of("adapters", Maps.of("preferences",
			Maps.of("maxBytes", 200L, "users", Maps.of(did, Maps.of("maxBytes", 2000L))))));
		try {
			PreferencesAdapter adapter = new PreferencesAdapter();
			venue.registerAdapter(adapter);
			venue.getVenueState().users().ensure(alice.getUserDID());
			RequestContext bob = RequestContext.of(Strings.create(did + "-bob"));
			String text = "x".repeat(1000);

			assertEquals(2000, adapter.limitFor(alice.getUserDID()), "the user's own limit");
			assertEquals(200, adapter.limitFor(bob.getUserDID()), "the venue's limit");
			call(adapter, alice, "command", "set", "path", "notes", "value", text);
			InvalidInputException tooLarge = refusal(adapter, bob, "command", "set", "path", "notes", "value", text);
			assertTrue(tooLarge.getMessage().startsWith(PreferencesAdapter.TOO_LARGE.substring(0, 16)), tooLarge.getMessage());

			// Lowered below what is stored: shrinking still works, growing does not.
			venue.configureAdapter("preferences", Maps.of("maxBytes", 200L));
			refusal(adapter, alice, "command", "set", "path", "more", "value", text);
			call(adapter, alice, "command", "remove", "path", "notes");
			call(adapter, alice, "command", "set", "path", "units", "value", "metric");
		} finally {
			venue.close();
		}
	}

	@Test
	public void testDefaultLimitWithoutConfiguration() {
		Engine venue = Engine.createTemp(Maps.empty());
		try {
			PreferencesAdapter adapter = new PreferencesAdapter();
			venue.registerAdapter(adapter);
			assertEquals(PreferencesAdapter.DEFAULT_MAX_BYTES, adapter.limitFor(alice.getUserDID()));
		} finally {
			venue.close();
		}
	}

	private static ACell call(PreferencesAdapter adapter, RequestContext ctx, Object... input) {
		return adapter.invokeFuture(ctx, Maps.empty(), Maps.of(input)).join();
	}

	private static InvalidInputException refusal(PreferencesAdapter adapter, RequestContext ctx, Object... input) {
		CompletionException e = assertThrows(CompletionException.class, () -> call(adapter, ctx, input));
		return assertInstanceOf(InvalidInputException.class, e.getCause());
	}

	// ========== configuration ==========

	@Test
	public void testConfigurationIsValidated() {
		PreferencesAdapter adapter = new PreferencesAdapter();
		assertTrue(adapter.configure(Maps.of("maxBytes", 8192L, "users", Maps.of("did:key:z6MkA", Maps.of("maxBytes", 100L))), true));
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("maxBites", 1L), true));
		assertTrue(adapter.configure(Maps.of("maxBites", 1L), false), "unknown settings pass when not strict");
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("maxBytes", 0L), false));
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("maxBytes", "big"), false));
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("users", Vectors.empty()), false));
		assertThrows(ConfigurationException.class, () -> adapter.configure(Maps.of("users", Maps.of("bob", Maps.empty())), false));
		assertThrows(ConfigurationException.class,
			() -> adapter.configure(Maps.of("users", Maps.of("did:key:z6MkA", Maps.of("limit", 1L))), true));
	}
}

package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import convex.auth.ucan.Capability;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for the {@code skills} venue op ({@code v/ops/skills}) — read-only
 * skill discovery, end-to-end through the catalog (resolution, schema,
 * dispatch) via {@code invokeInternal}.
 */
public class SkillsAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_CONTENT_TEXT = Strings.intern("contentText");
	private static final AString K_SOURCES = Strings.intern("sources");
	private static final AString K_REF = Strings.intern("ref");
	private static final AString K_BODY = Strings.intern("body");
	private static final AString K_SKILLS = Strings.intern("skills");

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private ACell invoke(AMap<AString, ACell> input, RequestContext c) {
		try {
			return engine.jobs().invokeInternal("v/ops/skills", input, c).get(5, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private void write(String path, ACell value, RequestContext c) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), c)
				.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	// ========== list ==========

	@Test
	public void testListEmptyReturnsNull() {
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);
		assertNull(result, "no skills → null (the assemble-op contract)");
	}

	@Test
	public void testListWorkspaceSkills() {
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")), ctx);
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);
		assertNotNull(result);
		assertTrue(result.toString().contains("- alpha — Alpha skill"), result.toString());
	}

	@Test
	public void testListDefaultSources() {
		// Omitted sources default to ["w/skills", "v/skills"].
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")), ctx);
		ACell result = invoke(Maps.of(Strings.create("command"), Strings.create("list")), ctx);
		assertNotNull(result);
		assertTrue(result.toString().contains("- alpha — Alpha skill"), result.toString());
	}

	@Test
	public void testListVenueSkills() {
		// Venue skills are written under the venue's own identity (the
		// VenueGlobalsResolver write gate) and publicly discoverable.
		RequestContext venueCtx = RequestContext.of(engine.getDIDString());
		// A skill goes INSIDE a skillset: v/skills holds directories, never
		// skills, and SkillsLibraryTest enforces that repo-wide.
		write("v/skills/demo/venue-demo", Maps.of(
			Fields.DESCRIPTION, Strings.create("A venue-installed skill")), venueCtx);
		try {
			ACell result = invoke(Maps.of(
				Strings.create("command"), Strings.create("list"),
				K_SOURCES, Vectors.of(Strings.create("v/skills/demo"))), ctx);
			assertNotNull(result);
			assertTrue(result.toString().contains("- venue-demo — A venue-installed skill"), result.toString());
		} finally {
			// Shared-engine hygiene: leave v/skills as shipped.
			try {
				engine.jobs().invokeInternal("v/ops/covia/delete",
					Maps.of(Fields.PATH, Strings.create("v/skills/demo")), venueCtx)
					.get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}

	// ========== read ==========

	@Test
	public void testReadByName() {
		write("w/skills/reader", Maps.of(
			Fields.DESCRIPTION, Strings.create("Reads things"),
			Strings.create("skill"), Maps.of(
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read")))), ctx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("reader"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);

		assertEquals("reader", RT.getIn(result, Fields.NAME).toString());
		assertEquals("Reads things", RT.getIn(result, Fields.DESCRIPTION).toString());
		assertNull(RT.getIn(result, K_BODY), "contentless skill (a pure toolset) has no body");
		assertEquals("w/skills/reader", RT.getIn(result, Fields.PATH).toString());
		AVector<?> tools = (AVector<?>) RT.getIn(result, Fields.TOOLS);
		assertEquals(1, tools.count());
	}

	@Test
	public void testReadReportsContributedSkillSources() {
		write("w/skills/router", Maps.of(
			Fields.DESCRIPTION, Strings.create("Find specialist skills"),
			Strings.create("skill"), Maps.of(
				K_SKILLS, Vectors.of(Strings.create("w/specialists")))), ctx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("router"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);

		assertEquals(Vectors.of(Strings.create("w/specialists")), RT.getIn(result, K_SKILLS));
	}

	@Test
	public void testReadInlineContentBody() {
		write("w/skills/notes", Maps.of(
			Fields.DESCRIPTION, Strings.create("House notes"),
			Fields.CONTENT, Maps.of(Strings.create("inline"),
				Strings.create("Always write results to w/analysis."))), ctx);

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("notes"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), ctx);

		assertEquals("Always write results to w/analysis.", RT.getIn(result, K_BODY).toString());
	}

	@Test
	public void testReadByRefWithContentBody() {
		AMap<AString, ACell> storeInput = Maps.of(
			Fields.METADATA, Maps.of(
				Fields.NAME, Strings.create("asset-skill"),
				Fields.DESCRIPTION, Strings.create("From an asset")),
			K_CONTENT_TEXT, Strings.create("The full body text"));
		ACell stored;
		try {
			stored = engine.jobs().invokeInternal("v/ops/asset/store", storeInput, ctx)
				.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		Hash h = AssetAdapter.parseAssetId(RT.ensureString(RT.getIn(stored, Fields.ID)));
		String ref = "a/" + h.toHexString();

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			K_REF, Strings.create(ref)), ctx);

		assertEquals("asset-skill", RT.getIn(result, Fields.NAME).toString());
		assertEquals("The full body text", RT.getIn(result, K_BODY).toString());
		assertEquals(ref, RT.getIn(result, Fields.PATH).toString());
	}

	@Test
	public void testReadRequiresExactlyOneOfNameAndRef() {
		try {
			invoke(Maps.of(Strings.create("command"), Strings.create("read")), ctx);
			fail("neither name nor ref should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
		}
		try {
			invoke(Maps.of(
				Strings.create("command"), Strings.create("read"),
				Fields.NAME, Strings.create("x"),
				K_REF, Strings.create("w/skills/x")), ctx);
			fail("both name and ref should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("exactly one"), e.getMessage());
		}
	}

	@Test
	public void testUnknownCommandRejected() {
		try {
			invoke(Maps.of(Strings.create("command"), Strings.create("write")), ctx);
			fail("unknown command should be rejected");
		} catch (RuntimeException e) {
			assertTrue(e.getMessage().contains("list | read"), e.getMessage());
		}
	}

	// ========== operator configuration ==========

	/**
	 * The shipped defaults are what an unconfigured venue answers from, and
	 * they are published so a client can discover the entry point rather than
	 * hardcoding {@code v/skills/root}.
	 */
	@Test
	public void testDefaultSourcesArePublished() {
		SkillsAdapter adapter = (SkillsAdapter) engine.getAdapter("skills");
		AMap<AString, ACell> info = adapter.info();
		assertEquals(SkillsAdapter.DEFAULT_SKILLSETS, info.get(SkillsAdapter.K_DEFAULT_SKILLSETS));
		// No individually-named defaults to publish, so the key stays absent.
		assertNull(info.get(SkillsAdapter.K_DEFAULT_SKILLS));

		ACell published = engine.resolvePath(
			Strings.create("v/info/adapters/skills"), ctx);
		assertEquals(SkillsAdapter.DEFAULT_SKILLSETS,
			RT.getIn(published, "defaultSkillsets"),
			"the entry point should be discoverable at v/info/adapters/skills");
	}

	@Test
	public void testMalformedDefaultsAreRejectedAtConfigureTime() {
		SkillsAdapter adapter = (SkillsAdapter) engine.getAdapter("skills");
		// Not an array.
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of(
				SkillsAdapter.K_DEFAULT_SKILLSETS, Strings.create("v/skills/root")), true))
			.getMessage().contains("must be an array"));
		// Array of the wrong element type.
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of(
				SkillsAdapter.K_DEFAULT_SKILLS, Vectors.of(CVMLong.create(42))), true))
			.getMessage().contains("ref strings"));
		// Well-formed settings are accepted, and an empty config is fine.
		assertTrue(adapter.configure(Maps.of(
			SkillsAdapter.K_DEFAULT_SKILLSETS, Vectors.of(Strings.create("v/skills/root"))), true));
		assertTrue(adapter.configure(Maps.empty(), true));
		assertTrue(adapter.configure(null, true));
	}

	/**
	 * A venue that curates its own library answers {@code list} from it with
	 * no caller changes — the point of making the default configurable.
	 *
	 * <p>Uses its OWN engine: adapter configuration is venue-global state, and
	 * this suite runs methods in parallel, so reconfiguring the shared
	 * TestEngine would change what every other test sees.</p>
	 */
	@Test
	public void testConfiguredDefaultSkillsetIsUsed() throws Exception {
		Engine own = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			Config.ADAPTERS, Maps.of("skills", Maps.of(
				SkillsAdapter.K_DEFAULT_SKILLSETS,
				Vectors.of(Strings.create("v/skills/house"))))));
		Engine.addDemoAssets(own);
		try {
			RequestContext venueCtx = own.venueContext();
			own.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create("v/skills/house/house-rules"),
					Fields.VALUE, Maps.of(Fields.DESCRIPTION,
						Strings.create("The house way of doing things"))), venueCtx)
				.get(5, TimeUnit.SECONDS);

			ACell result = own.jobs().invokeInternal("v/ops/skills",
				Maps.of(Strings.create("command"), Strings.create("list")),
				RequestContext.of(TestEngine.uniqueDID("skills-cfg"))).get(5, TimeUnit.SECONDS);
			assertNotNull(result);
			assertTrue(result.toString().contains("- house-rules — The house way of doing things"),
				result.toString());
			assertFalse(result.toString().contains("- covia — "),
				"the shipped root skillset is no longer the default: " + result);

			// info() follows the effective configuration, not the shipped default.
			SkillsAdapter adapter = (SkillsAdapter) own.getAdapter("skills");
			assertEquals(Vectors.of(Strings.create("v/skills/house")),
				adapter.info().get(SkillsAdapter.K_DEFAULT_SKILLSETS));

			// Runtime reconfiguration reaches the same read path.
			own.configureAdapter("skills", Maps.empty());
			assertEquals(SkillsAdapter.DEFAULT_SKILLSETS,
				adapter.info().get(SkillsAdapter.K_DEFAULT_SKILLSETS));
		} finally {
			own.close();
		}
	}

	// ========== capability gating ==========

	/**
	 * Listing DEGRADES: an unreadable source renders a visible diagnostic line
	 * rather than failing the call, so a caller sees what they can see. The
	 * denial is still real — no name, description or body leaks through it.
	 */
	@Test
	public void testListDegradesOnUnreadableSource() {
		write("w/skills/private", Maps.of(
			Fields.NAME, Strings.create("private"),
			Fields.DESCRIPTION, Strings.create("Owner-only")), ctx);
		RequestContext denied = ctx.withCaps(Vectors.empty());

		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), denied);
		String index = String.valueOf(result);
		assertTrue(index.contains("[skills source w/skills — unavailable:"), index);
		assertFalse(index.contains("Owner-only"), "a denied source must not leak: " + index);
		assertFalse(index.contains("- private —"), "a denied source must not leak: " + index);
	}

	@Test
	public void testReadDeniedWithoutReadCapability() {
		write("w/skills/private2", Maps.of(
			Fields.DESCRIPTION, Strings.create("Owner-only"),
			Strings.create("content"), Maps.of(
				Strings.create("inline"), Strings.create("Do not expose"))), ctx);
		RequestContext denied = ctx.withCaps(Vectors.empty());

		// By direct ref...
		assertThrows(AuthException.class, () -> invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			K_REF, Strings.create("w/skills/private2")), denied));

		// ...and by name across sources: the body must not leak either way.
		assertThrows(AuthException.class, () -> invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("private2"),
			K_SOURCES, Vectors.of(Strings.create("w/skills"))), denied));
	}

	/** The pins are per source, so a grant covering one still denies another. */
	@Test
	public void testCapabilityPinsArePerSource() {
		// Declares `name`, so the index pass reads metadata only. A skill
		// WITHOUT a name falls back to its path segment, but only after
		// attempting a content read for frontmatter — which pins asset/read.
		write("w/skills/mine", Maps.of(
			Fields.NAME, Strings.create("mine"),
			Fields.DESCRIPTION, Strings.create("Readable")), ctx);
		// A scope covering only the caller's own w/skills subtree.
		RequestContext scoped = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/skills"),
				Strings.create("can"), Capability.CRUD_READ)));

		// Both sources at once: the granted one lists, the other degrades to a
		// diagnostic — the pin is per source, not per call.
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(
				Strings.create("w/skills"), Strings.create("w/elsewhere"))), scoped);
		String index = String.valueOf(result);
		assertTrue(index.contains("- mine — Readable"), index);
		assertTrue(index.contains("[skills source w/elsewhere — unavailable:"), index);

		// read is a specific request, so the same denial is an error there.
		assertThrows(AuthException.class, () -> invoke(Maps.of(
			Strings.create("command"), Strings.create("read"),
			Fields.NAME, Strings.create("whatever"),
			K_SOURCES, Vectors.of(Strings.create("w/elsewhere"))), scoped),
			"read fails on a source outside the granted scope");
	}

	/**
	 * Indexing a skill needs only {@code crud/read} over its path — including a
	 * skill that omits {@code name}. That used to differ: a name-less skill
	 * forced a content read during the index pass and so demanded
	 * {@code asset/read}, making the same skillset readable or not depending on
	 * what its author happened to write.
	 */
	@Test
	public void testIndexingNamelessSkillNeedsOnlyCrudRead() {
		// No `name`: resolution falls back to the path segment.
		write("w/eithercap/nameless", Maps.of(
			Fields.DESCRIPTION, Strings.create("No name field")), ctx);

		RequestContext crudOnly = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/eithercap"),
				Strings.create("can"), Capability.CRUD_READ)));
		ACell listed = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/eithercap"))), crudOnly);
		assertTrue(String.valueOf(listed).contains("- nameless — No name field"),
			String.valueOf(listed));

		// No read grant at all still degrades to a diagnostic, leaking nothing.
		ACell none = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/eithercap"))), ctx.withCaps(Vectors.empty()));
		assertTrue(String.valueOf(none).contains("unavailable:"), String.valueOf(none));
		assertFalse(String.valueOf(none).contains("No name field"), String.valueOf(none));
	}

	/**
	 * A PATH is namespace-scoped: {@code crud/read} over it is required, and an
	 * {@code asset/read} grant does not substitute. The public read-only scope
	 * grants {@code asset/read} UNSCOPED ({@code with: ""}) — safe for content
	 * addressing, where you must hold the hash to ask — so honouring it against
	 * a path would turn it into a licence to read any user's workspace.
	 */
	@Test
	public void testAssetReadDoesNotSubstituteForPathRead() {
		write("w/pathscope/skill", Maps.of(
			Fields.NAME, Strings.create("skill"),
			Fields.DESCRIPTION, Strings.create("Path-scoped")), ctx);

		RequestContext assetOnly = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/pathscope"),
				Strings.create("can"), Abilities.ASSET_READ)));
		ACell result = invoke(Maps.of(
			Strings.create("command"), Strings.create("list"),
			K_SOURCES, Vectors.of(Strings.create("w/pathscope"))), assetOnly);
		assertTrue(String.valueOf(result).contains("unavailable:"), String.valueOf(result));
		assertFalse(String.valueOf(result).contains("Path-scoped"), String.valueOf(result));
	}
}

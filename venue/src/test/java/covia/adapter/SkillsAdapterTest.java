package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
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
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Abilities;
import covia.api.Fields;
import covia.exception.AuthException;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for the skills venue ops — {@code v/ops/skills/list} and
 * {@code v/ops/skills/read} — end-to-end through the catalog (resolution,
 * schema, dispatch) via {@code invokeInternal}.
 */
public class SkillsAdapterTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_SKILL    = Strings.intern("skill");
	private static final AString K_SKILLSET = Strings.intern("skillset");
	private static final AString K_BODY     = Strings.intern("body");
	private static final AString K_SKILLS   = Strings.intern("skills");
	private static final AString K_SKILLSETS = Strings.intern("skillsets");

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private ACell call(String op, AMap<AString, ACell> input, RequestContext c) {
		try {
			return engine.jobs().invokeInternal(op, input, c).get(5, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private ACell list(String skillset, RequestContext c) {
		AMap<AString, ACell> input = (skillset == null) ? Maps.empty()
			: Maps.of(K_SKILLSET, Strings.create(skillset));
		return call("v/ops/skills/list", input, c);
	}

	private ACell read(String skill, RequestContext c) {
		return call("v/ops/skills/read", Maps.of(K_SKILL, Strings.create(skill)), c);
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

	private void write(String path, ACell value) {
		write(path, value, ctx);
	}

	/** The listing entry at one path, or null when absent. */
	private static ACell at(ACell listing, String path) {
		return RT.getIn(listing, path);
	}

	// ========== list ==========

	@Test
	public void testListPairsPathWithMetadata() {
		write("w/skills/alpha", Maps.of(
			Fields.NAME, Strings.create("alpha"),
			Fields.DESCRIPTION, Strings.create("Alpha skill")));
		write("w/skills/beta", Maps.of(Fields.DESCRIPTION, Strings.create("Beta skill")));

		ACell listing = list("w/skills", ctx);
		assertNotNull(listing);
		// Keyed by RESOLVED PATH: a name alone does not say where to read it from.
		assertEquals("alpha", RT.getIn(at(listing, "w/skills/alpha"), "name").toString());
		assertEquals("Alpha skill",
			RT.getIn(at(listing, "w/skills/alpha"), "description").toString());
		// name falls back to the path segment when metadata omits it
		assertEquals("beta", RT.getIn(at(listing, "w/skills/beta"), "name").toString());
		// content identity travels with the entry
		assertNotNull(RT.getIn(at(listing, "w/skills/alpha"), "id"));
	}

	@Test
	public void testListOmitsNonSkills() {
		write("w/mixed/real", Maps.of(Fields.DESCRIPTION, Strings.create("A real skill")));
		write("w/mixed/nested/inner", Maps.of(Fields.DESCRIPTION, Strings.create("Nested")));
		write("w/mixed/junk", Vectors.of(CVMLong.create(1), CVMLong.create(2)));

		ACell listing = list("w/mixed", ctx);
		assertNotNull(at(listing, "w/mixed/real"));
		// A listing answers "what can I load here" — not what else is lying around.
		assertNull(at(listing, "w/mixed/nested"));
		assertNull(at(listing, "w/mixed/junk"));
		assertEquals(1, RT.ensureMap(listing).count());
	}

	@Test
	public void testListEmptySkillsetIsEmptyMap() {
		ACell listing = list("w/no-such-skillset", ctx);
		assertNotNull(listing, "a survey returns an empty map, not null");
		assertEquals(0, RT.ensureMap(listing).count());
	}

	@Test
	public void testListDefaultsToConfiguredSkillsets() {
		// Omitting skillset lists the venue's entry skillsets; the shipped
		// default includes v/skills/root, so its skills show with full paths.
		ACell listing = list(null, ctx);
		assertNotNull(listing);
		assertNotNull(at(listing, "v/skills/root/covia"),
			"the venue entry skillset should be listed by default: " + listing);
		assertEquals("covia", RT.getIn(at(listing, "v/skills/root/covia"), "name").toString());
	}

	@Test
	public void testListRejectsNonStringSkillset() {
		// Single arity: the error says what to pass instead.
		String message = assertThrows(RuntimeException.class,
			() -> call("v/ops/skills/list",
				Maps.of(K_SKILLSET, Vectors.of(Strings.create("w/skills"))), ctx)).getMessage();
		assertTrue(message.contains("skillset") || message.contains("string"), message);
	}

	// ========== read ==========

	@Test
	public void testReadByPath() {
		write("w/skills/reader", Maps.of(
			Fields.DESCRIPTION, Strings.create("Reads things"),
			Strings.create("content"), Maps.of(
				Strings.create("inline"), Strings.create("Read carefully.")),
			K_SKILL, Maps.of(
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read")),
				K_SKILLSETS, Vectors.of(Strings.create("w/specialists")))));

		ACell result = read("w/skills/reader", ctx);
		assertEquals("reader", RT.getIn(result, "name").toString());
		assertEquals("Reads things", RT.getIn(result, "description").toString());
		assertEquals("Read carefully.", RT.getIn(result, K_BODY).toString());
		assertEquals("w/skills/reader", RT.getIn(result, "path").toString());
		assertNotNull(RT.getIn(result, "id"));
		assertEquals(Vectors.of(Strings.create("v/ops/covia/read")), RT.getIn(result, "tools"));
		assertEquals(Vectors.of(Strings.create("w/specialists")), RT.getIn(result, K_SKILLSETS));
		assertNull(RT.getIn(result, K_SKILLS), "no individual child skills declared");
	}

	@Test
	public void testReadContentlessSkillHasNoBody() {
		write("w/skills/toolset", Maps.of(
			Fields.DESCRIPTION, Strings.create("A pure toolset"),
			K_SKILL, Maps.of(Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read")))));
		ACell result = read("w/skills/toolset", ctx);
		assertNull(RT.getIn(result, K_BODY), "a contentless skill is a pure toolset");
		assertEquals("A pure toolset", RT.getIn(result, "description").toString());
	}

	@Test
	public void testReadRequiresASkillRef() {
		String message = assertThrows(RuntimeException.class,
			() -> call("v/ops/skills/read", Maps.empty(), ctx)).getMessage();
		// The error has to say what to pass, since there is no name lookup.
		assertTrue(message.contains("skill"), message);
	}

	@Test
	public void testReadFailsOnMissingSkill() {
		assertThrows(RuntimeException.class, () -> read("w/skills/not-there", ctx),
			"a read is a specific request: absence is an error, not an omission");
	}

	// ========== capability gating ==========

	@Test
	public void testListDegradesOnUnreadableSkillset() {
		write("w/skills/private", Maps.of(
			Fields.NAME, Strings.create("private"),
			Fields.DESCRIPTION, Strings.create("Owner-only")), ctx);
		RequestContext denied = ctx.withCaps(Vectors.empty());

		ACell listing = list("w/skills", denied);
		assertEquals(0, RT.ensureMap(listing).count(),
			"a denied skillset contributes nothing: " + listing);
		assertFalse(String.valueOf(listing).contains("Owner-only"), String.valueOf(listing));
	}

	@Test
	public void testReadDeniedWithoutReadCapability() {
		write("w/skills/private2", Maps.of(
			Fields.DESCRIPTION, Strings.create("Owner-only"),
			Strings.create("content"), Maps.of(
				Strings.create("inline"), Strings.create("Do not expose"))), ctx);

		assertThrows(AuthException.class,
			() -> read("w/skills/private2", ctx.withCaps(Vectors.empty())),
			"read is a specific request, so a denial is an error");
	}

	@Test
	public void testCapabilityPinsArePerSkillset() {
		write("w/skills/mine", Maps.of(
			Fields.NAME, Strings.create("mine"),
			Fields.DESCRIPTION, Strings.create("Readable")), ctx);
		RequestContext scoped = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/skills"),
				Strings.create("can"), Capability.CRUD_READ)));

		assertNotNull(at(list("w/skills", scoped), "w/skills/mine"));
		assertEquals(0, RT.ensureMap(list("w/elsewhere", scoped)).count(),
			"a skillset outside the granted scope contributes nothing");
	}

	/**
	 * Indexing a skill needs only {@code crud/read} over its path — including a
	 * skill that omits {@code name}, which used to force a content read and so
	 * demand {@code asset/read}.
	 */
	@Test
	public void testListingNamelessSkillNeedsOnlyCrudRead() {
		write("w/eithercap/nameless", Maps.of(
			Fields.DESCRIPTION, Strings.create("No name field")), ctx);
		RequestContext crudOnly = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/eithercap"),
				Strings.create("can"), Capability.CRUD_READ)));

		ACell entry = at(list("w/eithercap", crudOnly), "w/eithercap/nameless");
		assertNotNull(entry, "a name-less skill should list under crud/read alone");
		assertEquals("nameless", RT.getIn(entry, "name").toString());
	}

	/**
	 * A PATH is namespace-scoped: {@code asset/read} does not substitute. The
	 * public read-only scope grants it UNSCOPED, so honouring it against a path
	 * would license reading any user's workspace.
	 */
	@Test
	public void testAssetReadDoesNotSubstituteForPathRead() {
		write("w/pathscope/skill", Maps.of(
			Fields.NAME, Strings.create("skill"),
			Fields.DESCRIPTION, Strings.create("Path-scoped")), ctx);
		RequestContext assetOnly = ctx.withCaps(Vectors.of(
			Maps.of(Strings.create("with"), Strings.create(did + "/w/pathscope"),
				Strings.create("can"), Abilities.ASSET_READ)));

		assertEquals(0, RT.ensureMap(list("w/pathscope", assetOnly)).count());
		assertThrows(AuthException.class, () -> read("w/pathscope/skill", assetOnly));
	}

	// ========== operator configuration ==========

	@Test
	public void testDefaultSourcesArePublished() {
		SkillsAdapter adapter = (SkillsAdapter) engine.getAdapter("skills");
		AMap<AString, ACell> info = adapter.info();
		assertEquals(SkillsAdapter.DEFAULT_SKILLSETS, info.get(SkillsAdapter.K_DEFAULT_SKILLSETS));
		assertNull(info.get(SkillsAdapter.K_DEFAULT_SKILLS));

		ACell published = engine.resolvePath(Strings.create("v/info/adapters/skills"), ctx);
		assertEquals(SkillsAdapter.DEFAULT_SKILLSETS, RT.getIn(published, "defaultSkillsets"),
			"the entry point should be discoverable at v/info/adapters/skills");
	}

	@Test
	public void testMalformedDefaultsAreRejectedAtConfigureTime() {
		SkillsAdapter adapter = (SkillsAdapter) engine.getAdapter("skills");
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of(
				SkillsAdapter.K_DEFAULT_SKILLSETS, Strings.create("v/skills/root")), true))
			.getMessage().contains("must be an array"));
		assertTrue(assertThrows(IllegalArgumentException.class,
			() -> adapter.configure(Maps.of(
				SkillsAdapter.K_DEFAULT_SKILLS, Vectors.of(CVMLong.create(42))), true))
			.getMessage().contains("ref strings"));
		assertTrue(adapter.configure(Maps.of(
			SkillsAdapter.K_DEFAULT_SKILLSETS, Vectors.of(Strings.create("v/skills/root"))), true));
		assertTrue(adapter.configure(Maps.empty(), true));
		assertTrue(adapter.configure(null, true));
	}

	/**
	 * A venue that curates its own library answers a default listing from it.
	 * Uses its OWN engine: adapter configuration is venue-global and this suite
	 * runs methods in parallel.
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

			ACell listing = own.jobs().invokeInternal("v/ops/skills/list", Maps.empty(),
				RequestContext.of(TestEngine.uniqueDID("skills-cfg"))).get(5, TimeUnit.SECONDS);
			assertNotNull(at(listing, "v/skills/house/house-rules"), String.valueOf(listing));
			assertNull(at(listing, "v/skills/root/covia"),
				"the shipped root skillset is no longer the default: " + listing);

			SkillsAdapter adapter = (SkillsAdapter) own.getAdapter("skills");
			assertEquals(Vectors.of(Strings.create("v/skills/house")),
				adapter.info().get(SkillsAdapter.K_DEFAULT_SKILLSETS));

			own.configureAdapter("skills", Maps.empty());
			assertEquals(SkillsAdapter.DEFAULT_SKILLSETS,
				adapter.info().get(SkillsAdapter.K_DEFAULT_SKILLSETS));
		} finally {
			own.close();
		}
	}
}

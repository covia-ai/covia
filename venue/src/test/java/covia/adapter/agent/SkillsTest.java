package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import covia.adapter.AssetAdapter;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Tests for {@link Skills} — the single resolver behind the skills index,
 * skill_load, and the skills venue op. Covers the skill-is-an-asset model:
 * metadata maps, asset content bodies, SKILL.md frontmatter, inline markdown,
 * string references, directories, the skill facet, and operation skills.
 */
public class SkillsTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_CONTENT_TEXT = Strings.intern("contentText");

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== fixtures ==========

	private void write(String path, ACell value) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), ctx)
				.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/** Stores an asset (optionally with text content); returns the bare a/<hash> ref. */
	private String store(ACell metadata, String contentText) {
		AMap<AString, ACell> input = Maps.of(Fields.METADATA, metadata);
		if (contentText != null) {
			input = input.assoc(K_CONTENT_TEXT, Strings.create(contentText));
		}
		try {
			ACell result = engine.jobs().invokeInternal("v/ops/asset/store", input, ctx)
				.get(5, TimeUnit.SECONDS);
			AString id = RT.ensureString(RT.getIn(result, Fields.ID));
			Hash h = AssetAdapter.parseAssetId(id);
			assertNotNull(h, "asset:store should return a parseable id");
			return "a/" + h.toHexString();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Skills.ResolvedSkill resolve(String ref) {
		return Skills.resolveRef(engine, ctx, Strings.create(ref));
	}

	@Test
	public void testDirectSkillLoadHonoursAgentReadScope() {
		write("w/skills/private", Maps.of(
			Fields.DESCRIPTION, Strings.create("Owner-only instructions"),
			"content", Maps.of("inline", Strings.create("Do not expose"))));

		RequestContext denied = ctx.withCaps(Vectors.empty());
		assertThrows(covia.exception.AuthException.class, () ->
			Skills.load(engine, denied, Vectors.of(Strings.create("w/skills")),
				Maps.of("ref", Strings.create("w/skills/private")), Maps.empty()),
			"skill_load is a read action even though it is implemented by the harness");
	}

	// ========== resolveRef: the body chain ==========

	@Test
	public void testContentlessSkillIsAPureToolset() {
		// A metadata map with no content anywhere: no body. The description
		// stays what it is — the index one-liner — and doubles as the display
		// fallback, never as documentation.
		write("w/skills/basic", Maps.of(Fields.DESCRIPTION, Strings.create("Does X")));
		Skills.ResolvedSkill s = resolve("w/skills/basic");
		assertEquals("basic", s.name());              // no name field → last path segment
		assertEquals("Does X", s.description());
		assertNull(s.body());
		assertEquals("Does X", s.displayBody());
		assertEquals(0, s.toolOps().count());
		assertEquals(0, s.contextEntries().count());
	}

	@Test
	public void testAssetSkillContentBody() {
		ACell meta = Maps.of(
			Fields.NAME, Strings.create("pdf-processing"),
			Fields.DESCRIPTION, Strings.create("Extract text from PDFs"),
			Skills.K_SKILL, Maps.of(Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/read"))));
		String ref = store(meta, "## PDF processing\nUse covia_read on the source.");
		Skills.ResolvedSkill s = resolve(ref);
		assertEquals("pdf-processing", s.name());
		assertEquals("Extract text from PDFs", s.description());
		assertEquals("## PDF processing\nUse covia_read on the source.", s.body());
		assertEquals(1, s.toolOps().count());
		assertEquals("v/ops/covia/read", s.toolOps().get(0).toString());
	}

	@Test
	public void testSkillMdFrontmatterSuppliesNameAndDescription() {
		// Anthropic-style SKILL.md stored as-is: metadata carries neither name
		// nor description; frontmatter is the compatibility fallback.
		String ref = store(Maps.empty(),
			"---\nname: pdf\ndescription: Extracts PDFs\n---\n\n## Body\nsteps here");
		Skills.ResolvedSkill s = resolve(ref);
		assertEquals("pdf", s.name());
		assertEquals("Extracts PDFs", s.description());
		assertEquals("## Body\nsteps here", s.body());  // frontmatter stripped, one blank line eaten
	}

	@Test
	public void testMetadataWinsOverFrontmatter() {
		ACell meta = Maps.of(
			Fields.NAME, Strings.create("meta-name"),
			Fields.DESCRIPTION, Strings.create("Meta description"));
		String ref = store(meta, "---\nname: fm-name\ndescription: FM description\n---\nBody");
		Skills.ResolvedSkill s = resolve(ref);
		assertEquals("meta-name", s.name());
		assertEquals("Meta description", s.description());
		assertEquals("Body", s.body());                 // frontmatter still stripped from the body
	}

	// ========== content.inline bodies ==========

	@Test
	public void testInlineContentSkill() {
		// content.inline is the standard metadata-declared inline content —
		// an asset-model feature (Engine.resolveContent), not a skills one.
		write("w/skills/inline", Maps.of(
			Fields.DESCRIPTION, Strings.create("Inline one"),
			Fields.CONTENT, Maps.of(Strings.create("inline"), Strings.create("Body here"))));
		Skills.ResolvedSkill s = resolve("w/skills/inline");
		assertEquals("inline", s.name());
		assertEquals("Inline one", s.description());
		assertEquals("Body here", s.body());
	}

	@Test
	public void testInlineContentFrontmatter() {
		// A SKILL.md pasted into content.inline: frontmatter supplies
		// name/description when the metadata lacks them, and is stripped.
		write("w/skills/fm", Maps.of(
			Fields.CONTENT, Maps.of(Strings.create("inline"),
				Strings.create("---\r\nname: pdf\r\ndescription: CRLF one\r\n---\r\nBody"))));
		Skills.ResolvedSkill s = resolve("w/skills/fm");
		assertEquals("pdf", s.name());
		assertEquals("CRLF one", s.description());
		assertEquals("Body", s.body());
	}

	@Test
	public void testPlainStringValueRejected() {
		// A non-reference string is not a skill — inline bodies go in
		// content.inline, not raw string values (no parallel structures).
		write("w/skills/nodesc", Strings.create("Just some plain instructions text"));
		RuntimeException e = assertThrows(RuntimeException.class, () -> resolve("w/skills/nodesc"));
		assertTrue(e.getMessage().contains("content.inline"), e.getMessage());
	}

	// ========== string references (one hop) ==========

	@Test
	public void testStringRefSkill() {
		ACell meta = Maps.of(
			Fields.NAME, Strings.create("shared-skill"),
			Fields.DESCRIPTION, Strings.create("A shared skill"));
		String assetRef = store(meta, "Shared body");
		write("w/skills/shared", Strings.create(assetRef));

		Skills.ResolvedSkill s = resolve("w/skills/shared");
		assertEquals("shared-skill", s.name());
		assertEquals("Shared body", s.body());
		// Canonical path stays the directory address — the loads key the body
		// re-resolves from each turn.
		assertEquals("w/skills/shared", s.path().toString());
	}

	@Test
	public void testStringRefChainNotFollowed() {
		String assetRef = store(Maps.of(Fields.DESCRIPTION, Strings.create("target")), null);
		write("w/skills/hop1", Strings.create("w/skills/hop2"));
		write("w/skills/hop2", Strings.create(assetRef));
		RuntimeException e = assertThrows(RuntimeException.class, () -> resolve("w/skills/hop1"));
		assertTrue(e.getMessage().contains("chains are not followed"));
	}

	// ========== operation skills (facet composition) ==========

	@Test
	public void testOperationSkillOffersItselfAsTool() {
		ACell meta = Maps.of(
			Fields.NAME, Strings.create("echo-skill"),
			Fields.DESCRIPTION, Strings.create("Echoes input"),
			Fields.OPERATION, Maps.of(Fields.ADAPTER, Strings.create("test:echo")),
			Skills.K_SKILL, Maps.empty());
		String ref = store(meta, "How to use echo");
		Skills.ResolvedSkill s = resolve(ref);
		assertEquals("How to use echo", s.body());
		assertEquals(1, s.toolOps().count());
		assertEquals(ref, s.toolOps().get(0).toString());   // the skill's own ref
	}

	// ========== the skill facet ==========

	@Test
	public void testFacetBudgetAndContext() {
		write("w/skills/rich", Maps.of(
			Fields.DESCRIPTION, Strings.create("Rich skill"),
			Skills.K_SKILL, Maps.of(
				Strings.create("budget"), CVMLong.create(5000),
				Strings.create("context"), Vectors.of(
					Maps.of(Strings.create("ref"), Strings.create("w/docs/x"),
						Strings.create("label"), Strings.create("X"))))));
		Skills.ResolvedSkill s = resolve("w/skills/rich");
		assertEquals(5000, s.budget());
		assertEquals(1, s.contextEntries().count());
	}

	@Test
	public void testFacetShapeErrors() {
		write("w/skills/badfacet", Maps.of(
			Fields.DESCRIPTION, Strings.create("Bad"),
			Skills.K_SKILL, Strings.create("not-a-map")));
		assertTrue(assertThrows(RuntimeException.class, () -> resolve("w/skills/badfacet"))
			.getMessage().contains("must be a map"));

		write("w/skills/badtools", Maps.of(
			Fields.DESCRIPTION, Strings.create("Bad"),
			Skills.K_SKILL, Maps.of(Fields.TOOLS, Strings.create("v/ops/covia/read"))));
		assertTrue(assertThrows(RuntimeException.class, () -> resolve("w/skills/badtools"))
			.getMessage().contains("must be an array"));

		write("w/skills/badtoolentry", Maps.of(
			Fields.DESCRIPTION, Strings.create("Bad"),
			Skills.K_SKILL, Maps.of(Fields.TOOLS, Vectors.of(CVMLong.create(42)))));
		assertTrue(assertThrows(RuntimeException.class, () -> resolve("w/skills/badtoolentry"))
			.getMessage().contains("operation ref strings"));
	}

	// ========== directories, listing, first-wins ==========

	@Test
	public void testDirectoryListingAndFirstWins() {
		write("w/sk1/dup", Maps.of(Fields.DESCRIPTION, Strings.create("First")));
		write("w/sk1/solo", Maps.of(Fields.DESCRIPTION, Strings.create("Only in sk1")));
		write("w/sk2/dup", Maps.of(Fields.DESCRIPTION, Strings.create("Second")));

		AVector<ACell> sources = Vectors.of(Strings.create("w/sk1"), Strings.create("w/sk2"));
		List<Skills.SkillIndexEntry> entries = Skills.listSkills(engine, ctx, sources);
		assertEquals(2, entries.size());   // dup deduped first-wins, solo

		Skills.SkillIndexEntry dup = entries.stream()
			.filter(e -> "dup".equals(e.name())).findFirst().orElseThrow();
		assertEquals("First", dup.description());
		assertEquals("w/sk1/dup", dup.path().toString());

		// resolveByName follows the same first-wins order
		assertEquals("First", Skills.resolveByName(engine, ctx, sources, "dup").description());
	}

	@Test
	public void testAbsentSourceSkippedQuietly() {
		List<Skills.SkillIndexEntry> entries = Skills.listSkills(engine, ctx,
			Vectors.of(Strings.create("w/no-such-dir")));
		assertTrue(entries.isEmpty());
		assertNull(Skills.renderIndex(engine, ctx, Vectors.of(Strings.create("w/no-such-dir")), null));
	}

	@Test
	public void testNonStringSourceThrows() {
		assertThrows(IllegalArgumentException.class, () ->
			Skills.listSkills(engine, ctx, Vectors.of(CVMLong.create(1))));
	}

	@Test
	public void testResolveByNameNotFound() {
		RuntimeException e = assertThrows(RuntimeException.class, () ->
			Skills.resolveByName(engine, ctx, Vectors.of(Strings.create("w/skills")), "ghost"));
		assertTrue(e.getMessage().contains("ghost"));
	}

	// ========== the rendered index ==========

	@Test
	public void testRenderIndex() {
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")));
		write("w/skills/broken", Maps.of(Fields.NAME, Strings.create("broken")));  // no description
		write("w/notadir", Strings.create("just a text value with spaces"));

		AVector<ACell> sources = Vectors.of(
			Strings.create("w/skills"), Strings.create("w/notadir"));
		String index = Skills.renderIndex(engine, ctx, sources, null);
		assertNotNull(index);
		assertTrue(index.contains("- alpha — Alpha skill"), index);
		assertTrue(index.contains("- broken — INVALID: missing description"), index);
		assertTrue(index.contains("[skills source w/notadir — unavailable:"), index);
		assertFalse(index.contains("(loaded)"), index);
	}

	@Test
	public void testRenderIndexLoadedMarker() {
		write("w/skills/alpha", Maps.of(Fields.DESCRIPTION, Strings.create("Alpha skill")));
		Skills.ResolvedSkill s = resolve("w/skills/alpha");
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/skills/alpha"), Skills.buildSkillLoadMeta(2000, s));

		String index = Skills.renderIndex(engine, ctx,
			Vectors.of(Strings.create("w/skills")), loads);
		assertTrue(index.contains("- alpha — Alpha skill (loaded)"), index);
	}

	// ========== loads-entry integration ==========

	@Test
	public void testBuildSkillLoadMetaAndIsSkillEntry() {
		Hash id = Strings.create("skill-id-test").getHash();
		Skills.ResolvedSkill s = new Skills.ResolvedSkill("pdf", "Extracts PDFs", "body",
			Vectors.of(Strings.create("v/ops/covia/read")), Vectors.empty(), 0,
			Strings.create("w/skills/pdf"), id);
		AMap<AString, ACell> meta = Skills.buildSkillLoadMeta(2000, s);

		assertTrue(Skills.isSkillEntry(meta));
		assertEquals(2000, ((CVMLong) meta.get(Strings.create("budget"))).longValue());
		assertTrue(((CVMLong) meta.get(Strings.create("ts"))).longValue() > 0);
		assertEquals("pdf", meta.get(Strings.create("label")).toString());
		assertEquals(s.toolOps(), meta.get(Fields.TOOLS));
		// Nothing identity-shaped persists on the entry — dedup is live.
		assertNull(meta.get(Strings.create("id")));

		assertFalse(Skills.isSkillEntry(null));
		assertFalse(Skills.isSkillEntry(Maps.of(Strings.create("budget"), CVMLong.create(1))));
	}

	@Test
	public void testContentIdentityAcrossPaths() {
		// The same skill asset reached via a directory string-ref and via its
		// hash form resolves to ONE content identity — the dedup key.
		ACell meta = Maps.of(
			Fields.NAME, Strings.create("shared"),
			Fields.DESCRIPTION, Strings.create("A shared skill"));
		String assetRef = store(meta, "Shared body");
		write("w/skills/shared", Strings.create(assetRef));

		Skills.ResolvedSkill viaRef = resolve("w/skills/shared");
		Skills.ResolvedSkill direct = resolve(assetRef);
		assertNotNull(viaRef.id());
		assertEquals(viaRef.id(), direct.id());
		assertFalse(viaRef.path().equals(direct.path()));

		// findLoadedDuplicate spots it under the other address — by LIVE
		// re-resolution of the loaded entry, nothing stored
		AMap<AString, ACell> loads = Maps.of(direct.path(), Skills.buildSkillLoadMeta(2000, direct));
		assertEquals(direct.path(), Skills.findLoadedDuplicate(engine, ctx, loads, viaRef.id()));
		assertNull(Skills.findLoadedDuplicate(engine, ctx, loads, Strings.create("other").getHash()));

		// ... and the index (loaded) marker matches by identity, not path
		String index = Skills.renderIndex(engine, ctx,
			Vectors.of(Strings.create("w/skills")), loads);
		assertTrue(index.contains("- shared — A shared skill (loaded)"), index);
	}

	@Test
	public void testLoadsToolDefsResolvesAndDedups() {
		// The generic "a loads entry may declare tools" rule — skill entries
		// are the first producer, but the mechanism is kind-agnostic.
		Skills.ResolvedSkill s = new Skills.ResolvedSkill("x", "d", "b",
			Vectors.of(Strings.create("v/ops/covia/read")), Vectors.empty(), 0,
			Strings.create("w/skills/x"), Strings.create("x-id").getHash());
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/skills/x"), Skills.buildSkillLoadMeta(2000, s));

		java.util.Map<String, AString> toolMap = new java.util.HashMap<>();
		AVector<ACell> defs = ContextBuilder.loadsToolDefs(engine, ctx, loads,
			java.util.Set.of(), toolMap);
		assertEquals(1, defs.count());
		assertEquals("covia_read", RT.getIn(defs.get(0), Fields.NAME).toString());
		assertEquals("v/ops/covia/read", toolMap.get("covia_read").toString());

		// Dedup against names already offered outside the loads mechanism
		AVector<ACell> none = ContextBuilder.loadsToolDefs(engine, ctx, loads,
			java.util.Set.of("covia_read"), new java.util.HashMap<>());
		assertEquals(0, none.count());

		// A PLAIN (non-skill) entry with tools contributes too — kind-agnostic.
		AMap<AString, ACell> plain = Maps.of(
			Strings.create("w/data/pack"), Maps.of(
				Strings.create("budget"), CVMLong.create(500),
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/covia/list"))));
		AVector<ACell> plainDefs = ContextBuilder.loadsToolDefs(engine, ctx, plain,
			java.util.Set.of(), new java.util.HashMap<>());
		assertEquals(1, plainDefs.count());

		// Unresolvable tool refs are skipped (buildConfigTools' skip-with-warn).
		AMap<AString, ACell> broken = Maps.of(
			Strings.create("w/skills/y"), Maps.of(
				Strings.create("skill"), convex.core.data.prim.CVMBool.TRUE,
				Fields.TOOLS, Vectors.of(Strings.create("v/ops/no/such/op"))));
		assertEquals(0, ContextBuilder.loadsToolDefs(engine, ctx, broken,
			java.util.Set.of(), new java.util.HashMap<>()).count());
	}

	// ========== frontmatter parser ==========

	@Test
	public void testParseFrontmatter() {
		Skills.Frontmatter fm = Skills.parseFrontmatter("---\nname: x\ndescription: y\nextra: ignored\n---\nBody");
		assertEquals("x", fm.name());
		assertEquals("y", fm.description());
		assertEquals("Body", fm.body());

		assertNull(Skills.parseFrontmatter("No frontmatter here"));
		assertNull(Skills.parseFrontmatter("---\nunclosed: frontmatter"));
		assertNull(Skills.parseFrontmatter(null));

		// Delimiter must open the text
		assertNull(Skills.parseFrontmatter("\n---\nname: x\n---\nBody"));
	}
}

package covia.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import covia.adapter.agent.Skills;
import covia.api.Fields;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * SKILL.md files as Covia skills: the resolver's content-reference paths
 * ({@code file://}, {@code dlfs/}) and the {@code skills/parse} and
 * {@code skills/import} ops, end-to-end through the catalog. Uses a bespoke
 * engine because it needs a host file root.
 */
public class SkillsImportTest {

	@TempDir static Path work;
	private static Engine engine;
	private AString did;
	private RequestContext ctx;

	private static final AString K_SOURCE   = Fields.SOURCE;
	private static final AString K_TEXT     = Fields.TEXT;
	private static final AString K_SKILL    = Strings.intern("skill");
	private static final AString K_SKILLSET = Strings.intern("skillset");
	private static final AString K_IGNORED  = Strings.intern("ignored");
	private static final AString K_EXISTED  = Strings.intern("existed");
	private static final AString K_BODY     = Strings.intern("body");

	private static final String AGENT_SKILL = """
		---
		name: agent
		description: >
		  Create, configure, and manage Covia agents.
		  Use when working with agents on a venue.
		argument-hint: "<create|list|query|reset> <agent-name>"
		allowed-tools: Bash(git:*) Read
		license: Apache-2.0
		metadata:
		  author: covia
		  version: "1.0"
		tools:
		  - v/ops/covia/read
		  - v/ops/covia/write
		---

		# Agent Management

		Body text here.
		""";

	@BeforeAll
	static void setup() throws IOException {
		engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			"file", Maps.of("roots", Maps.of("work", work.toAbsolutePath().toString()))));
		Engine.addDemoAssets(engine);
		Files.createDirectories(work.resolve("agent"));
		Files.writeString(work.resolve("agent/SKILL.md"), AGENT_SKILL);
		Files.createDirectories(work.resolve("nameless"));
		Files.writeString(work.resolve("nameless/SKILL.md"),
			"---\ndescription: A skill named by its directory.\n---\nNameless body.\n");
		Files.writeString(work.resolve("notes.txt"), "Just some notes, no frontmatter.\n");
	}

	@AfterAll
	static void teardown() {
		engine.close();
	}

	@BeforeEach
	public void setupContext(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private ACell call(String op, AMap<AString, ACell> input) {
		try {
			return engine.jobs().invokeInternal(op, input, ctx).get(5, TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			throw (e.getCause() instanceof RuntimeException re) ? re : new RuntimeException(e.getCause());
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private ACell parse(AMap<AString, ACell> input) {
		return call("v/ops/skills/parse", input);
	}

	private ACell importSkill(AMap<AString, ACell> input) {
		return call("v/ops/skills/import", input);
	}

	private void write(String path, ACell value) {
		call("v/ops/covia/write", Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value));
	}

	private static String str(ACell v, Object... path) {
		AString s = RT.ensureString(RT.getIn(v, path));
		return (s != null) ? s.toString() : null;
	}

	// ========== resolver: content references ==========

	@Test
	public void testStringRefToFileResolvesAsSkill() {
		write("w/skills/agent", Strings.create("file://work/agent/SKILL.md"));
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("w/skills/agent"));
		assertEquals("agent", s.name());
		assertEquals("Create, configure, and manage Covia agents. Use when working with agents on a venue.",
			s.description());                                        // folded scalar → one line
		assertTrue(s.body().startsWith("# Agent Management"));       // frontmatter stripped
		assertEquals(2, s.toolOps().count());                        // block sequence read
		assertEquals("w/skills/agent", s.path().toString());
	}

	@Test
	public void testDirectFileRefResolvesAsSkill() {
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("file://work/agent/SKILL.md"));
		assertEquals("agent", s.name());
		assertEquals("file://work/agent/SKILL.md", s.path().toString());
		// Same file under two addresses is one skill: identity is the synthesised metadata.
		write("w/skills/agent", Strings.create("file://work/agent/SKILL.md"));
		assertEquals(s.id(), Skills.resolveRef(engine, ctx, Strings.create("w/skills/agent")).id());
	}

	@Test
	public void testFileRefAsIndividualSkillSource() {
		List<Skills.SkillIndexEntry> idx = Skills.listSkills(engine, ctx,
			Skills.SkillSources.ofSkills(Vectors.of(Strings.create("file://work/agent/SKILL.md"))));
		assertEquals(1, idx.size());
		assertEquals("agent", idx.get(0).name());
		assertNull(idx.get(0).error());
	}

	@Test
	public void testNamelessSkillIsNamedByItsDirectory() {
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("file://work/nameless/SKILL.md"));
		assertEquals("nameless", s.name());
	}

	@Test
	public void testMissingFileIsDiagnosable() {
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> Skills.resolveRef(engine, ctx, Strings.create("file://work/absent/SKILL.md")));
		assertTrue(e.getMessage().contains("absent/SKILL.md"), e.getMessage());
	}

	@Test
	public void testFileWithoutFrontmatterIsNotASkill() {
		RuntimeException e = assertThrows(RuntimeException.class,
			() -> Skills.resolveRef(engine, ctx, Strings.create("file://work/notes.txt")));
		assertTrue(e.getMessage().contains("not a SKILL.md"), e.getMessage());
	}

	// ========== parse ==========

	@Test
	public void testParseFromSourceInline() {
		ACell out = parse(Maps.of(K_SOURCE, Strings.create("file://work/agent/SKILL.md")));
		assertEquals("agent", str(out, "name"));
		ACell meta = RT.getIn(out, "metadata");
		assertEquals("agent", str(meta, "name"));
		assertEquals("Create, configure, and manage Covia agents. Use when working with agents on a venue.",
			str(meta, "description"));
		assertEquals("text/markdown", str(meta, "content", "contentType"));
		assertTrue(str(meta, "content", "inline").startsWith("# Agent Management"));
		assertNull(RT.getIn(meta, "content", "ref"));
		assertEquals("Apache-2.0", str(meta, "license"));            // spec provenance field carried
		AVector<?> tools = RT.ensureVector(RT.getIn(meta, "skill", "tools"));
		assertEquals(2, tools.count());                              // facet carried: inline body has no frontmatter
		// Keys with no Covia meaning are reported, not silently dropped.
		AVector<?> ignored = RT.ensureVector(RT.getIn(out, K_IGNORED));
		assertEquals(Vectors.of(Strings.create("argument-hint"), Strings.create("allowed-tools"),
			Strings.create("metadata")), ignored);

		// The metadata is directly writable — and resolves as the same skill.
		write("w/skills/agent", meta);
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("w/skills/agent"));
		assertEquals("agent", s.name());
		assertTrue(s.body().startsWith("# Agent Management"));
		assertEquals(2, s.toolOps().count());
	}

	@Test
	public void testParseFromSourceRef() {
		ACell out = parse(Maps.of(K_SOURCE, Strings.create("file://work/agent/SKILL.md"),
			Fields.CONTENT, Fields.REF));
		ACell meta = RT.getIn(out, "metadata");
		assertEquals("file://work/agent/SKILL.md", str(meta, "content", "ref"));
		assertNull(RT.getIn(meta, "content", "inline"));
		assertNull(RT.getIn(meta, "skill"));                         // live: the frontmatter stays authoritative
	}

	@Test
	public void testParseFromText() {
		ACell out = parse(Maps.of(K_TEXT, Strings.create(
			"---\nname: quick\ndescription: 'Quoted description'\n---\nBody\n")));
		assertEquals("quick", str(out, "name"));
		assertEquals("Quoted description", str(out, "description"));
		assertEquals("Body\n", str(out, "metadata", "content", "inline"));
		assertNull(RT.getIn(out, K_IGNORED));
	}

	@Test
	public void testParseRequiresExactlyOneInput() {
		assertThrows(IllegalArgumentException.class, () -> parse(Maps.empty()));
		assertThrows(IllegalArgumentException.class, () -> parse(Maps.of(
			K_SOURCE, Strings.create("file://work/agent/SKILL.md"), K_TEXT, Strings.create("---\n---\n"))));
		// A ref binding needs something to bind to.
		assertThrows(IllegalArgumentException.class, () -> parse(Maps.of(
			K_TEXT, Strings.create("---\nname: x\ndescription: y\n---\n"), Fields.CONTENT, Fields.REF)));
		assertThrows(IllegalArgumentException.class, () -> parse(Maps.of(
			K_SOURCE, Strings.create("file://work/agent/SKILL.md"), Fields.CONTENT, Strings.create("copy"))));
	}

	@Test
	public void testParseRejectsNonSkillContent() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
			() -> parse(Maps.of(K_SOURCE, Strings.create("file://work/notes.txt"))));
		assertTrue(e.getMessage().contains("not a SKILL.md"), e.getMessage());
		IllegalArgumentException noDesc = assertThrows(IllegalArgumentException.class,
			() -> parse(Maps.of(K_TEXT, Strings.create("---\nname: x\n---\nBody"))));
		assertTrue(noDesc.getMessage().contains("description"), noDesc.getMessage());
		IllegalArgumentException noName = assertThrows(IllegalArgumentException.class,
			() -> parse(Maps.of(K_TEXT, Strings.create("---\ndescription: y\n---\nBody"))));
		assertTrue(noName.getMessage().contains("name"), noName.getMessage());
	}

	// ========== import ==========

	@Test
	public void testImportDefaultsToOwnSkills() {
		ACell out = importSkill(Maps.of(K_SOURCE, Strings.create("file://work/agent/SKILL.md")));
		assertEquals("w/skills/agent", str(out, "path"));
		assertEquals("agent", str(out, "name"));
		assertEquals("inline", str(out, "content"));
		assertEquals(false, RT.bool(RT.getIn(out, K_EXISTED)));
		assertNotNull(RT.getIn(out, K_IGNORED));

		// Discoverable and readable through the ordinary skill ops.
		ACell listing = call("v/ops/skills/list", Maps.of(K_SKILLSET, Strings.create("w/skills")));
		assertEquals("agent", str(listing, "w/skills/agent", "name"));
		ACell read = call("v/ops/skills/read", Maps.of(K_SKILL, Strings.create("w/skills/agent")));
		assertTrue(str(read, K_BODY).startsWith("# Agent Management"));
		assertEquals(2, RT.ensureVector(RT.getIn(read, "tools")).count());

		// Re-import replaces the entry.
		ACell again = importSkill(Maps.of(K_SOURCE, Strings.create("file://work/agent/SKILL.md")));
		assertEquals(true, RT.bool(RT.getIn(again, K_EXISTED)));
	}

	@Test
	public void testImportIntoNamedSkillset() {
		ACell out = importSkill(Maps.of(K_SOURCE, Strings.create("file://work/agent/SKILL.md"),
			K_SKILLSET, Strings.create("w/team-skills/")));
		assertEquals("w/team-skills/agent", str(out, "path"));
		ACell listing = call("v/ops/skills/list", Maps.of(K_SKILLSET, Strings.create("w/team-skills")));
		assertEquals("agent", str(listing, "w/team-skills/agent", "name"));
	}

	@Test
	public void testImportAsLiveRef() throws IOException {
		Files.createDirectories(work.resolve("live"));
		Files.writeString(work.resolve("live/SKILL.md"), "---\nname: live\ndescription: First.\n---\nOne.\n");
		ACell out = importSkill(Maps.of(K_SOURCE, Strings.create("file://work/live/SKILL.md"),
			Fields.CONTENT, Fields.REF));
		assertEquals("ref", str(out, "content"));
		ACell read = call("v/ops/skills/read", Maps.of(K_SKILL, Strings.create("w/skills/live")));
		assertEquals("One.\n", str(read, K_BODY));

		// Edits on disk show on the next read; the index line is a snapshot.
		Files.writeString(work.resolve("live/SKILL.md"), "---\nname: live\ndescription: Second.\n---\nTwo.\n");
		read = call("v/ops/skills/read", Maps.of(K_SKILL, Strings.create("w/skills/live")));
		assertEquals("Two.\n", str(read, K_BODY));
		assertEquals("First.", str(read, "description"));
	}

	@Test
	public void testImportFromDlfs() {
		call("v/ops/dlfs/create-drive", Maps.of(Fields.NAME, Strings.create("skills")));
		call("v/ops/dlfs/mkdir", Maps.of(
			Strings.create("drive"), Strings.create("skills"), Fields.PATH, Strings.create("pdf")));
		call("v/ops/dlfs/write", Maps.of(
			Strings.create("drive"), Strings.create("skills"),
			Fields.PATH, Strings.create("pdf/SKILL.md"),
			Fields.CONTENT, Strings.create("---\ndescription: PDF handling.\n---\nUse file_read.\n")));
		ACell out = importSkill(Maps.of(K_SOURCE, Strings.create("dlfs/skills/pdf/SKILL.md")));
		assertEquals("w/skills/pdf", str(out, "path"));               // name from the directory
		assertEquals("PDF handling.", str(out, "description"));
	}

	@Test
	public void testImportWritesNothingOnABadSource() {
		assertThrows(IllegalArgumentException.class, () -> importSkill(Maps.empty()));
		assertThrows(IllegalArgumentException.class,
			() -> importSkill(Maps.of(K_SOURCE, Strings.create("file://work/notes.txt"))));
		assertThrows(RuntimeException.class,
			() -> importSkill(Maps.of(K_SOURCE, Strings.create("file://work/absent/SKILL.md"))));
		// A directory is not a source.
		assertThrows(RuntimeException.class,
			() -> importSkill(Maps.of(K_SOURCE, Strings.create("file://work/agent"))));
		ACell listing = call("v/ops/skills/list", Maps.of(K_SKILLSET, Strings.create("w/skills")));
		assertTrue(((AMap<?, ?>) listing).isEmpty(), "nothing written: " + listing);
	}

	@Test
	public void testImportRejectsMalformedSkillset() {
		assertThrows(IllegalArgumentException.class, () -> importSkill(Maps.of(
			K_SOURCE, Strings.create("file://work/agent/SKILL.md"), K_SKILLSET, Vectors.of(Strings.create("w/skills")))));
		assertThrows(IllegalArgumentException.class, () -> importSkill(Maps.of(
			K_SOURCE, Strings.create("file://work/agent/SKILL.md"), K_SKILLSET, Strings.create("/"))));
		// Namespace rules are the lattice write's: a read-only namespace is refused there.
		assertThrows(RuntimeException.class, () -> importSkill(Maps.of(
			K_SOURCE, Strings.create("file://work/agent/SKILL.md"), K_SKILLSET, Strings.create("v/skills"))));
		assertFalse(Skills.listSkills(engine, ctx,
			Skills.SkillSources.ofSkillsets(Vectors.of(Strings.create("w/skills")))).stream()
			.anyMatch(e -> "agent".equals(e.name())));
	}
}

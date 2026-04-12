package covia.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.api.Fields;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;
import org.junit.jupiter.api.TestInfo;

/**
 * Tests for ContextBuilder — context assembly for agent LLM turns.
 *
 * <p>Tests the builder independently: config merge, system prompt, context
 * entries, inbox messages, tools, budget tracking, and CellExplorer rendering.
 * Uses {@link Engine#createTemp} for a lightweight venue instance.</p>
 */
public class ContextBuilderTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;
	private AString ALICE_DID;

	private static final AString K_ROLE    = Strings.intern("role");
	private static final AString K_CONTENT = Strings.intern("content");
	private static final AString K_CONFIG  = Strings.intern("config");
	private static final AString K_HISTORY = Strings.intern("history");
	private static final AString K_CONTEXT = Strings.intern("context");

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(ALICE_DID);
	}

	// ========== Config merge ==========

	@Test
	public void testConfigMergeRecordOnly() {
		AMap<AString, ACell> recordConfig = Maps.of(
			Strings.intern("systemPrompt"), Strings.create("Be helpful"));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(recordConfig, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertNotNull(result.config());
		assertEquals("Be helpful",
			RT.ensureString(result.config().get(Strings.intern("systemPrompt"))).toString());
	}

	@Test
	public void testConfigMergeStateOverrides() {
		AMap<AString, ACell> recordConfig = Maps.of(
			Strings.intern("model"), Strings.create("gpt-4o"));
		ACell state = Maps.of(K_CONFIG, Maps.of(
			Strings.intern("model"), Strings.create("gpt-3.5")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(recordConfig, state)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		// State config overrides record on merge (record.merge(state) — right wins)
		assertEquals("gpt-3.5",
			RT.ensureString(result.config().get(Strings.intern("model"))).toString());
	}

	@Test
	public void testConfigMergeStateOnly() {
		ACell state = Maps.of(K_CONFIG, Maps.of(
			Strings.intern("systemPrompt"), Strings.create("State prompt")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, state)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertNotNull(result.config());
		assertEquals("State prompt",
			RT.ensureString(result.config().get(Strings.intern("systemPrompt"))).toString());
	}

	// ========== System prompt ==========

	@Test
	public void testSystemPromptPrependedWhenMissing() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertTrue(result.history().count() > 0);
		ACell first = result.history().get(0);
		assertEquals("system", RT.ensureString(RT.getIn(first, K_ROLE)).toString());
		String content = RT.ensureString(RT.getIn(first, K_CONTENT)).toString();
		assertTrue(content.contains("helpful AI agent"));
	}

	@Test
	public void testCustomSystemPrompt() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("systemPrompt"), Strings.create("You are a financial analyst."));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		String content = RT.ensureString(RT.getIn(result.history().get(0), K_CONTENT)).toString();
		assertTrue(content.contains("financial analyst"));
	}

	@Test
	public void testExistingSystemPromptIsAlwaysReplaced() {
		// Per AGENT_CONTEXT_PLAN.md Option C, the system message is
		// always rebuilt fresh per turn — any system message at the
		// start of the starting vector is dropped and replaced with
		// the freshly composed identity + LATTICE_REFERENCE.
		AVector<ACell> existing = Vectors.of(
			(ACell) Maps.of(K_ROLE, Strings.intern("system"), K_CONTENT, Strings.create("Stale frozen prompt")),
			(ACell) Maps.of(K_ROLE, Strings.intern("user"), K_CONTENT, Strings.create("Hello")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(existing)
			.withTools()
			.build();

		// Still 2 messages: the fresh system + the preserved user
		assertEquals(2, result.history().count());
		String sysContent = RT.ensureString(RT.getIn(result.history().get(0), K_CONTENT)).toString();
		// Old "Stale frozen prompt" is gone — replaced with fresh default
		assertFalse(sysContent.contains("Stale frozen prompt"),
			"Stale system message should have been dropped");
		assertTrue(sysContent.contains("Covia platform"),
			"Fresh default identity prompt should be present");
		assertTrue(sysContent.contains("Covia Lattice"),
			"Lattice reference should be appended");
		// User message preserved at index 1
		String userContent = RT.ensureString(RT.getIn(result.history().get(1), K_CONTENT)).toString();
		assertEquals("Hello", userContent);
	}

	// ========== Context entries ==========

	@Test
	public void testContextEntriesFromConfig() {
		// Write workspace data
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/rules"),
				Strings.create("value"), Strings.create("Rule 1: validate all inputs")),
			ctx).awaitResult(5000);

		AMap<AString, ACell> config = Maps.of(
			K_CONTEXT, Vectors.of((ACell) Strings.create("w/rules")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.withContextEntries(Maps.empty())
			.withTools()
			.build();

		// Should have system prompt + context entry
		assertTrue(result.history().count() >= 2);
		// Find context message
		boolean found = false;
		for (long i = 0; i < result.history().count(); i++) {
			String content = RT.ensureString(RT.getIn(result.history().get(i), K_CONTENT)).toString();
			if (content.contains("Rule 1: validate all inputs")) { found = true; break; }
		}
		assertTrue(found, "Context entry should be resolved");
	}

	@Test
	public void testContextEntriesUseCellExplorer() {
		// Write structured map to workspace
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/structured"),
				Strings.create("value"), Maps.of(
					Strings.create("name"), Strings.create("Alice"),
					Strings.create("role"), Strings.create("analyst"))),
			ctx).awaitResult(5000);

		AMap<AString, ACell> config = Maps.of(
			K_CONTEXT, Vectors.of((ACell) Strings.create("w/structured")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.withContextEntries(Maps.empty())
			.withTools()
			.build();

		// CellExplorer renders as JSON5 — should contain key names
		boolean found = false;
		for (long i = 0; i < result.history().count(); i++) {
			String content = RT.ensureString(RT.getIn(result.history().get(i), K_CONTENT)).toString();
			if (content.contains("Alice") && content.contains("analyst")) { found = true; break; }
		}
		assertTrue(found, "Structured value should be rendered with CellExplorer");
	}

	// ========== Pending results ==========

	@Test
	public void testPendingResults() {
		AVector<ACell> pending = Vectors.of(
			(ACell) Maps.of(
				Fields.JOB_ID, Strings.create("abc123"),
				Fields.STATUS, Strings.create("COMPLETE"),
				Fields.OUTPUT, Strings.create("result data")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withPendingResults(pending)
			.withTools()
			.build();

		// Should have system prompt + pending message
		ACell last = result.history().get(result.history().count() - 1);
		String content = RT.ensureString(RT.getIn(last, K_CONTENT)).toString();
		assertTrue(content.contains("[Pending job results]"));
		assertTrue(content.contains("abc123"));
		assertTrue(content.contains("result data"));
	}

	@Test
	public void testNoPendingResults() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withPendingResults(null)
			.withTools()
			.build();

		// Only system prompt
		assertEquals(1, result.history().count());
	}

	// ========== Inbox messages ==========

	@Test
	public void testInboxStringMessage() {
		AVector<ACell> inbox = Vectors.of((ACell) Strings.create("Hello agent"));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withInboxMessages(inbox)
			.withTools()
			.build();

		ACell last = result.history().get(result.history().count() - 1);
		assertEquals("user", RT.ensureString(RT.getIn(last, K_ROLE)).toString());
		assertEquals("Hello agent", RT.ensureString(RT.getIn(last, K_CONTENT)).toString());
	}

	@Test
	public void testInboxMapMessageWithProvenance() {
		AVector<ACell> inbox = Vectors.of(
			(ACell) Maps.of(
				Fields.CALLER, Strings.create("did:key:z6MkBob"),
				Fields.MESSAGE, Strings.create("Please review the report")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withInboxMessages(inbox)
			.withTools()
			.build();

		ACell last = result.history().get(result.history().count() - 1);
		String content = RT.ensureString(RT.getIn(last, K_CONTENT)).toString();
		assertTrue(content.contains("[Message from: did:key:z6MkBob]"));
		assertTrue(content.contains("Please review the report"));
	}

	// ========== Empty state signal ==========

	@Test
	public void testEmptyStateSignalWhenNoInput() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withEmptyStateSignal(false)
			.withTools()
			.build();

		ACell last = result.history().get(result.history().count() - 1);
		String content = RT.ensureString(RT.getIn(last, K_CONTENT)).toString();
		assertTrue(content.contains("No pending tasks"));
	}

	@Test
	public void testNoSignalWhenInputPresent() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withEmptyStateSignal(true)
			.withTools()
			.build();

		// Only system prompt
		assertEquals(1, result.history().count());
	}

	// ========== Tools ==========

	@Test
	public void testDefaultToolsCached() {
		// Default tools resolve to the SAME vector across calls — the
		// per-engine cache returns the cached AVector instance.
		ContextBuilder.ContextResult r1 = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();
		ContextBuilder.ContextResult r2 = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		// Same Engine → same cached tool vector instance (identity, not just equal)
		assertSame(r1.tools(), r2.tools(),
			"Default tools should be cached per Engine and return the same instance");

		// Each builder still gets its own configToolMap (not the cached one)
		assertNotSame(r1.configToolMap(), r2.configToolMap(),
			"configToolMap should be a per-build copy, not the cached map");
		// But the contents should match
		assertEquals(r1.configToolMap().keySet(), r2.configToolMap().keySet());
	}

	@Test
	public void testDefaultToolsBuilt() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertTrue(result.tools().count() > 0, "Should have default tools");
		assertTrue(result.configToolMap().size() > 0, "Should have tool mappings");
		// Verify a known default tool is present
		assertTrue(result.configToolMap().containsKey("covia_read"));
	}

	@Test
	public void testSystemPromptIncludesSessionContext() {
		// Every agent should see current date and venue name.
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		String content = sys.toString();
		assertTrue(content.contains("Current date:"),
			"System prompt should include current date: " + content);
		assertTrue(content.contains("Venue:"),
			"System prompt should include venue name: " + content);
	}

	@Test
	public void testSystemPromptIncludesModelWhenConfigured() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("model"), Strings.create("gpt-4.1-mini"));
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		assertTrue(sys.toString().contains("Model: gpt-4.1-mini"),
			"System prompt should include model name when configured");
	}

	@Test
	public void testSystemPromptOmitsCapsSectionWhenUnrestricted() {
		// No caps in config = unrestricted = no caps section
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		assertFalse(sys.toString().contains("Your capabilities (caps)"),
			"Unrestricted agents should NOT see a caps section");
	}

	@Test
	public void testSystemPromptIncludesCapsWhenSet() {
		// Caps in config = caps section in system prompt with each entry
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("caps"), Vectors.of(
				(ACell) Maps.of(
					Strings.intern("with"), Strings.create("w/decisions/"),
					Strings.intern("can"), Strings.create("crud")),
				(ACell) Maps.of(
					Strings.intern("with"), Strings.create("w/"),
					Strings.intern("can"), Strings.create("crud/read"))));
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		String content = sys.toString();
		assertTrue(content.contains("Your capabilities (caps)"),
			"Caps section header should be present");
		assertTrue(content.contains("crud on w/decisions/"),
			"Each cap should be listed with ability and resource: " + content);
		assertTrue(content.contains("crud/read on w/"),
			"Each cap should be listed with ability and resource: " + content);
		assertTrue(content.contains("Capability denied"),
			"Caps section should explain what happens on denial");
		assertTrue(content.contains("Retrying the same call does not help"),
			"Caps section should tell the LLM not to loop on denials");
	}

	@Test
	public void testSystemPromptIncludesEmptyCapsExplicitly() {
		// Empty caps array = deny all = should be explicitly stated
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("caps"), Vectors.empty());
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		assertTrue(sys.toString().contains("(none)"),
			"Empty caps should be explicitly stated as no capabilities");
	}

	@Test
	public void testSystemPromptIncludesLatticeReference() {
		// Default identity prompt → lattice reference appended
		ContextBuilder.ContextResult defaultResult = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString defaultSys = extractSystemContent(defaultResult.history());
		assertNotNull(defaultSys);
		assertTrue(defaultSys.toString().contains("Covia Lattice"),
			"Default system prompt should include lattice reference");
		assertTrue(defaultSys.toString().contains("Workspace"),
			"Default system prompt should describe workspace namespace");
		assertTrue(defaultSys.toString().contains("v/ops"),
			"Default system prompt should mention v/ops catalog");

		// Custom identity prompt → lattice reference STILL appended
		AMap<AString, ACell> customConfig = Maps.of(
			Strings.intern("systemPrompt"),
			Strings.create("You are Carol the AP Approver. Be concise."));
		ContextBuilder.ContextResult customResult = new ContextBuilder(engine, ctx)
			.withConfig(customConfig, null)
			.withSystemPrompt(Vectors.empty())
			.build();
		AString customSys = extractSystemContent(customResult.history());
		assertNotNull(customSys);
		assertTrue(customSys.toString().contains("Carol the AP Approver"),
			"Custom identity prompt should still appear");
		assertTrue(customSys.toString().contains("Covia Lattice"),
			"Lattice reference should also be appended for custom prompts");
	}

	private static AString extractSystemContent(AVector<ACell> history) {
		if (history == null || history.count() == 0) return null;
		ACell first = history.get(0);
		if (!(first instanceof AMap)) return null;
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> firstMap = (AMap<AString, ACell>) first;
		AString role = RT.ensureString(firstMap.get(Strings.intern("role")));
		if (role == null || !"system".equals(role.toString())) return null;
		return RT.ensureString(firstMap.get(Strings.intern("content")));
	}

	@Test
	public void testToolDescriptionContainsCatalogPath() {
		// The LLM-visible description should be prefixed with "Operation: <path>"
		// so the model sees the lattice address co-located with the tool name.
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		boolean foundCoviaRead = false;
		for (long i = 0; i < result.tools().count(); i++) {
			AMap<AString, ACell> tool = RT.ensureMap(result.tools().get(i));
			AString name = RT.ensureString(tool.get(Strings.intern("name")));
			if (!"covia_read".equals(name.toString())) continue;
			foundCoviaRead = true;

			AString description = RT.ensureString(tool.get(Strings.intern("description")));
			assertNotNull(description, "covia_read tool should have a description");
			String descStr = description.toString();
			assertTrue(descStr.startsWith("Operation: v/ops/covia/read"),
				"Description should be prefixed with 'Operation: v/ops/covia/read', got: "
					+ descStr.substring(0, Math.min(80, descStr.length())));
			// And the original description body should still be there
			assertTrue(descStr.contains("Reads a single value"),
				"Description body should still be present");
		}
		assertTrue(foundCoviaRead, "covia_read tool should be in the default tool list");
	}

	@Test
	public void testDefaultToolsDisabled() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("defaultTools"), CVMBool.FALSE);

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertEquals(0, result.tools().count(), "No default tools when disabled");
	}

	@Test
	public void testCapsExtracted() {
		AVector<ACell> capsVec = Vectors.of((ACell) Strings.create("v/ops/grid/run"));
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("caps"), capsVec);

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertNotNull(result.caps());
		assertEquals(1, result.caps().count());
		assertNotNull(result.capsCtx());
	}

	@Test
	public void testNoCapsUnrestricted() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertNull(result.caps());
		// capsCtx should be the original ctx when no caps
		assertEquals(ctx.getCallerDID(), result.capsCtx().getCallerDID());
	}

	// ========== Budget tracking ==========

	@Test
	public void testBudgetTracking() {
		ContextBuilder builder = new ContextBuilder(engine, ctx, 100_000);
		assertEquals(0, builder.getConsumed());
		assertEquals(100_000, builder.getRemaining());

		builder.withConfig(null, null)
			.withSystemPrompt(Vectors.empty());

		assertTrue(builder.getConsumed() > 0, "System prompt should consume budget");
		assertTrue(builder.getRemaining() < 100_000);
		assertEquals(100_000, builder.getConsumed() + builder.getRemaining());
	}

	@Test
	public void testBudgetInResult() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx, 200_000)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withTools()
			.build();

		assertTrue(result.bytesConsumed() > 0);
		assertTrue(result.bytesRemaining() > 0);
		assertEquals(200_000, result.bytesConsumed() + result.bytesRemaining());
	}

	// ========== Full build ==========

	@Test
	public void testFullBuildShape() {
		AVector<ACell> inbox = Vectors.of((ACell) Strings.create("Do something"));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withContextEntries(Maps.empty())
			.withPendingResults(null)
			.withInboxMessages(inbox)
			.withEmptyStateSignal(true)
			.withTools()
			.build();

		// History: system prompt + inbox message
		assertEquals(2, result.history().count());
		assertEquals("system", RT.ensureString(RT.getIn(result.history().get(0), K_ROLE)).toString());
		assertEquals("user", RT.ensureString(RT.getIn(result.history().get(1), K_ROLE)).toString());

		// Tools present
		assertTrue(result.tools().count() > 0);
		assertNotNull(result.configToolMap());
		assertNotNull(result.capsCtx());
	}

	// ========== Static helpers ==========

	@Test
	public void testParseConfigToolEntryString() {
		AString[] parsed = ContextBuilder.parseConfigToolEntry(Strings.create("v/ops/agent/create"));
		assertNotNull(parsed);
		assertEquals("v/ops/agent/create", parsed[0].toString());
		assertNull(parsed[1]);
		assertNull(parsed[2]);
	}

	@Test
	public void testParseConfigToolEntryMap() {
		ACell entry = Maps.of(
			Fields.OPERATION, Strings.create("v/ops/grid/run"),
			Strings.intern("name"), Strings.create("myTool"),
			Strings.intern("description"), Strings.create("My tool"));
		AString[] parsed = ContextBuilder.parseConfigToolEntry(entry);
		assertNotNull(parsed);
		assertEquals("v/ops/grid/run", parsed[0].toString());
		assertEquals("myTool", parsed[1].toString());
		assertEquals("My tool", parsed[2].toString());
	}

	@Test
	public void testParseConfigToolEntryInvalid() {
		assertNull(ContextBuilder.parseConfigToolEntry(CVMLong.create(42)));
		assertNull(ContextBuilder.parseConfigToolEntry(null));
	}

	@Test
	public void testDeriveToolName() {
		assertEquals("override",
			ContextBuilder.deriveToolName(Strings.create("override"), Strings.create("asset"), Strings.create("op:name")));
		assertEquals("asset",
			ContextBuilder.deriveToolName(null, Strings.create("asset"), Strings.create("op:name")));
		assertEquals("op_name",
			ContextBuilder.deriveToolName(null, null, Strings.create("op:name")));
	}

	@Test
	public void testBuildToolDefinition() {
		AMap<AString, ACell> def = ContextBuilder.buildToolDefinition("myTool",
			Strings.create("Does things"), null);
		assertEquals("myTool", RT.ensureString(RT.getIn(def, Strings.intern("name"))).toString());
		assertEquals("Does things", RT.ensureString(RT.getIn(def, Strings.intern("description"))).toString());
		assertNotNull(RT.getIn(def, Strings.intern("parameters")));
	}

	// ========== Dynamic context (load/unload, context map, safety valve) ==========

	@Test public void testLoadedPathsResolution() {
		// Write data to workspace via operation
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/test-data"),
				Strings.create("value"), Maps.of(Strings.create("key"), Strings.create("value"))),
			ctx).awaitResult(5000);

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/test-data"), Maps.of(
				Strings.create("budget"), CVMLong.create(500)));

		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult result = builder
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withLoadedPaths(loads)
			.withTools()
			.build();

		// Should have at least one message from the loaded path
		boolean found = false;
		for (long i = 0; i < result.history().count(); i++) {
			AString content = RT.ensureString(RT.getIn(result.history().get(i), "content"));
			if (content != null && content.toString().contains("key")) found = true;
		}
		assertTrue(found, "Loaded path should appear in context");
	}

	@Test public void testLoadedPathsMissingSkipped() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/nonexistent/path"), Maps.of(
				Strings.create("budget"), CVMLong.create(500)));

		ContextBuilder builder = new ContextBuilder(engine, ctx);
		// Should not throw
		builder.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withLoadedPaths(loads)
			.withTools()
			.build();
	}

	@Test public void testContextMapContent() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/data"), Maps.of(
				Strings.create("budget"), CVMLong.create(800),
				Strings.create("label"), Strings.create("Test Data")));

		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult result = builder
			.withConfig(null, null)
			.withSystemPrompt(Vectors.empty())
			.withContextMap(loads)
			.withTools()
			.build();

		// Find the context map message
		boolean foundMap = false;
		for (long i = 0; i < result.history().count(); i++) {
			AString content = RT.ensureString(RT.getIn(result.history().get(i), "content"));
			if (content != null && content.toString().contains("[Context Map]")) {
				foundMap = true;
				String text = content.toString();
				assertTrue(text.contains("budget:"), "Should show budget");
				assertTrue(text.contains("w/data"), "Should list loaded path");
				assertTrue(text.contains("Test Data"), "Should show label");
				assertTrue(text.contains("800B"), "Should show path budget");
			}
		}
		assertTrue(foundMap, "Should have context map message");
	}

	@Test public void testContextMapWarningAt70Pct() {
		// Use a tiny budget so context map triggers warning
		ContextBuilder builder = new ContextBuilder(engine, ctx, 100);
		// Fill most of the budget with a system prompt
		AVector<ACell> bigHistory = Vectors.of(
			Maps.of(Strings.create("role"), Strings.create("system"),
				Strings.create("content"), Strings.create("x".repeat(80))));
		ContextBuilder.ContextResult result = builder
			.withConfig(null, null)
			.withSystemPrompt(bigHistory)
			.withContextMap(null)
			.build();

		boolean foundWarning = false;
		for (long i = 0; i < result.history().count(); i++) {
			AString content = RT.ensureString(RT.getIn(result.history().get(i), "content"));
			if (content != null && content.toString().contains("WARNING")) foundWarning = true;
		}
		assertTrue(foundWarning, "Should warn when budget > 70%");
	}

	@Test public void testSafetyValveNoPruneBelow90() {
		ContextBuilder builder = new ContextBuilder(engine, ctx); // 180k budget
		builder.withConfig(null, null).withSystemPrompt(Vectors.empty());

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/a"), Maps.of(Strings.create("budget"), CVMLong.create(500)));

		AMap<AString, ACell> result = builder.applySafetyValve(loads);
		assertEquals(1, result.count(), "Should not prune below 90%");
	}

	@Test public void testSafetyValvePrunesLIFO() {
		// Use a tiny budget and fill it way past 90%
		ContextBuilder builder = new ContextBuilder(engine, ctx, 100);
		AVector<ACell> bigHistory = Vectors.of(
			Maps.of(Strings.create("role"), Strings.create("system"),
				Strings.create("content"), Strings.create("x".repeat(95))));
		builder.withConfig(null, null).withSystemPrompt(bigHistory);
		// Consumed is now > 90 bytes out of 100

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/old"), Maps.of(
				Strings.create("budget"), CVMLong.create(10),
				Strings.create("ts"), CVMLong.create(1000)),
			Strings.create("w/new"), Maps.of(
				Strings.create("budget"), CVMLong.create(10),
				Strings.create("ts"), CVMLong.create(2000)));

		AMap<AString, ACell> result = builder.applySafetyValve(loads);
		// Should prune newest first (w/new has ts=2000)
		assertTrue(result.count() < loads.count(), "Should prune at least one entry");
	}
}

package covia.adapter.agent;

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
import covia.adapter.agent.ContextBuilder;
import covia.api.Fields;
import covia.grid.Job;
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
	private static final AString K_CONTEXT = Strings.intern("context");

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(ALICE_DID);
	}

	// ========== Config (single slot — #144) ==========

	@Test
	public void testConfigFromRecord() {
		AMap<AString, ACell> recordConfig = Maps.of(
			Strings.intern("systemPrompt"), Strings.create("Be helpful"));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(recordConfig)
			.withSystemPrompt()
			.withTools()
			.build();

		assertNotNull(result.config());
		assertEquals("Be helpful",
			RT.ensureString(result.config().get(Strings.intern("systemPrompt"))).toString());
	}

	/** Config has a single home: record.config. A config map inside state is
	 *  runtime data the builder never reads (#144). */
	@Test
	public void testStateConfigIsNotRead() {
		AMap<AString, ACell> recordConfig = Maps.of(
			Strings.intern("model"), Strings.create("gpt-4o"));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(recordConfig)
			.withSystemPrompt()
			.withTools()
			.build();

		assertEquals("gpt-4o",
			RT.ensureString(result.config().get(Strings.intern("model"))).toString());
	}

	// ========== System prompt ==========

	@Test
	public void testSystemPromptPrependedWhenMissing() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
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
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();

		String content = RT.ensureString(RT.getIn(result.history().get(0), K_CONTENT)).toString();
		assertTrue(content.contains("financial analyst"));
	}

	@Test
	public void testWithSystemPromptIsIdempotent() {
		// Calling withSystemPrompt twice must leave a single system message —
		// the second call drops the leading system message and rebuilds fresh.
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
			.withSystemPrompt()
			.withTools()
			.build();

		assertEquals(1, result.history().count(),
			"Repeated withSystemPrompt must not stack system messages");
		String sysContent = RT.ensureString(RT.getIn(result.history().get(0), K_CONTENT)).toString();
		assertTrue(sysContent.contains("Covia platform"),
			"Default identity prompt should be present");
		assertTrue(sysContent.contains("Covia Lattice"),
			"Lattice reference should be appended");
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
			.withConfig(config)
			.withSystemPrompt()
			.withContextEntries()
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
	public void testInvalidConfigContextThrows() {
		// config.context present but not an array → loud failure (fix the config),
		// not a silent drop.
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Strings.create("not-an-array"));
		ContextBuilder b = new ContextBuilder(engine, ctx).withConfig(config);
		RuntimeException ex = assertThrows(RuntimeException.class,
			() -> b.withContextEntries());
		assertTrue(ex.getMessage().contains("config.context"), "message should name the bad field");
	}

	@Test
	public void testAbsentContextIsFine() {
		// No context key anywhere → no error, just no context entries.
		AMap<AString, ACell> config = Maps.of(Strings.intern("systemPrompt"), Strings.create("Be helpful"));
		assertDoesNotThrow(() -> new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withContextEntries()
			.build());
	}

	// ========== withSessionId (report-back handle) ==========

	@Test
	public void testSessionIdInSystemPrompt() {
		// The in-scope session id is the agent's handle for reporting back
		// into its own conversation from deferred work (scheduled
		// agent:request {sessionId}), so it must be visible in context.
		convex.core.data.Blob sid = convex.core.data.Blob.fromHex("00aa00aa00aa00aa00aa00aa00aa00aa");
		ContextBuilder.ContextResult withSid = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("systemPrompt"), Strings.create("Hi")))
			.withSessionId(sid)
			.withSystemPrompt()
			.build();
		String prompt = RT.ensureString(RT.getIn(withSid.history().get(0), K_CONTENT)).toString();
		assertTrue(prompt.contains("Session: 00aa00aa00aa00aa00aa00aa00aa00aa"), prompt);

		// Hex-string form works too; absent session → no Session line.
		ContextBuilder.ContextResult hexForm = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("systemPrompt"), Strings.create("Hi")))
			.withSessionId(Strings.create("beef0001"))
			.withSystemPrompt()
			.build();
		assertTrue(RT.ensureString(RT.getIn(hexForm.history().get(0), K_CONTENT))
			.toString().contains("Session: beef0001"));

		ContextBuilder.ContextResult without = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("systemPrompt"), Strings.create("Hi")))
			.withSystemPrompt()
			.build();
		assertFalse(RT.ensureString(RT.getIn(without.history().get(0), K_CONTENT))
			.toString().contains("Session:"));
	}

	// ========== withSkillsIndex (config.skills — SKILLS.md §4) ==========

	private void writeSkill(String path, String description) {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create(path),
				Strings.create("value"), Maps.of(
					Strings.create("description"), Strings.create(description))),
			ctx).awaitResult(5000);
	}

	/** Concatenated content of every message in the built history. */
	private static String allContent(ContextBuilder.ContextResult result) {
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < result.history().count(); i++) {
			AString c = RT.ensureString(RT.getIn(result.history().get(i), K_CONTENT));
			if (c != null) sb.append(c).append('\n');
		}
		return sb.toString();
	}

	@Test
	public void testSkillsIndexInjected() {
		writeSkill("w/skills/alpha", "Alpha skill");
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("skillsets"), Vectors.of((ACell) Strings.create("w/skills")));

		ContextBuilder builder = new ContextBuilder(engine, ctx);
		ContextBuilder.ContextResult result = builder
			.withConfig(config)
			.withSystemPrompt()
			.withContextEntries()
			.withSkillsIndex(null)
			.withTools()
			.build();

		String all = allContent(result);
		assertTrue(all.contains("[Skills]"), all);
		assertTrue(all.contains("- alpha — Alpha skill"), all);
		assertTrue(all.contains("advertised skill-loading control"),
			"preamble should explain the capability without coupling to an alias");
		assertFalse(all.contains("skill_load"),
			"durable preamble should not hard-code a provider-facing alias");
		assertFalse(all.contains("(loaded)"), all);

		// Budget-tracked: the same build without the index consumes less.
		ContextBuilder.ContextResult without = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withContextEntries()
			.withTools()
			.build();
		assertTrue(result.bytesConsumed() > without.bytesConsumed(),
			"skills index must be tracked against the budget");
	}

	@Test
	public void testSkillsIndexLoadedMarker() {
		writeSkill("w/skills/alpha", "Alpha skill");
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("skillsets"), Vectors.of((ACell) Strings.create("w/skills")));
		AMap<AString, ACell> effectiveLoads = Maps.of(
			Strings.create("w/skills/alpha"), Maps.of(
				Strings.create("skill"), CVMBool.TRUE,
				Strings.create("budget"), CVMLong.create(2000)));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSkillsIndex(effectiveLoads)
			.build();

		assertTrue(allContent(result).contains("- alpha — Alpha skill (loaded)"));
	}

	@Test
	public void testLoadedSkillContributesToSkillsIndex() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Fields.PATH, Strings.create("w/root-skill"), Fields.VALUE, Maps.of(
				Fields.NAME, Strings.create("root-skill"),
				Fields.DESCRIPTION, Strings.create("Reveals specialists"),
				Skills.K_SKILL, Maps.of(Skills.K_SKILLSETS,
					Vectors.of(Strings.create("w/specialists"))))), ctx).awaitResult(5000);
		writeSkill("w/specialists/reviewer", "Review a result");
		Skills.ResolvedSkill root = Skills.resolveRef(engine, ctx, Strings.create("w/root-skill"));
		AMap<AString, ACell> loads = Maps.of(root.path(), Skills.buildSkillLoadMeta(2000, root));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSkillsIndex(loads)
			.build();

		String all = allContent(result);
		assertTrue(all.contains("- reviewer — Review a result"), all);
		assertTrue(all.contains("may also reveal more skills"), all);
		assertFalse(all.contains("- root-skill —"),
			"a direct-ref bootstrap need not make itself a configured source");
	}

	@Test
	public void testSkillsIndexAbsentOrEmpty() {
		// No config.skills → no block.
		ContextBuilder.ContextResult none = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("systemPrompt"), Strings.create("Hi")))
			.withSystemPrompt()
			.withSkillsIndex(null)
			.build();
		assertFalse(allContent(none).contains("[Skills]"));

		// Empty sources / sources that resolve to nothing → no block either.
		ContextBuilder.ContextResult empty = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("skillsets"), Vectors.empty()))
			.withSkillsIndex(null)
			.build();
		assertFalse(allContent(empty).contains("[Skills]"));

		ContextBuilder.ContextResult absent = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("skillsets"),
				Vectors.of((ACell) Strings.create("w/no-skills-here"))))
			.withSkillsIndex(null)
			.build();
		assertFalse(allContent(absent).contains("[Skills]"));
	}

	@Test
	public void testInvalidConfigSkillsThrows() {
		// Malformed config.skills fails loudly — same rule as config.context.
		ContextBuilder notArray = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("skillsets"), Strings.create("w/skills")));
		RuntimeException e1 = assertThrows(RuntimeException.class,
			() -> notArray.withSkillsIndex(null));
		assertTrue(e1.getMessage().contains("config.skills"), e1.getMessage());

		ContextBuilder badEntry = new ContextBuilder(engine, ctx)
			.withConfig(Maps.of(Strings.intern("skillsets"), Vectors.of(CVMLong.create(42))));
		RuntimeException e2 = assertThrows(RuntimeException.class,
			() -> badEntry.withSkillsIndex(null));
		assertTrue(e2.getMessage().contains("config.skills"), e2.getMessage());
	}

	// ========== Skill entries in loads (SKILLS.md §6) ==========

	private AMap<AString, ACell> alphaSkillLoads() {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/skills/alpha"),
				Strings.create("value"), Maps.of(
					Strings.create("description"), Strings.create("Alpha skill"),
					Strings.create("content"), Maps.of(
						Strings.create("inline"), Strings.create("## Alpha\nDo the thing.")),
					Strings.create("skill"), Maps.of(
						Strings.create("context"), Vectors.of(Maps.of(
							Strings.create("text"), Strings.create("Alpha extra context"),
							Strings.create("label"), Strings.create("Alpha notes")))))),
			ctx).awaitResult(5000);
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("w/skills/alpha"));
		return Maps.of(Strings.create("w/skills/alpha"), Skills.buildSkillLoadMeta(2000, s));
	}

	@Test
	public void testSkillEntryRendersBodyAndContext() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withLoadedPaths(alphaSkillLoads())
			.build();
		String all = allContent(result);
		assertTrue(all.contains("[Skill: alpha — w/skills/alpha]\n## Alpha\nDo the thing."),
			"body renders verbatim under the [Skill:] label: " + all);
		assertTrue(all.contains("[Context: Alpha notes]"), all);
		assertTrue(all.contains("Alpha extra context"), all);
	}

	@Test
	public void testVanishedSkillRendersVisibly() {
		// A loaded skill that no longer resolves must NOT silently disappear.
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/skills/ghost"), Maps.of(
				Strings.create("skill"), CVMBool.TRUE,
				Strings.create("budget"), CVMLong.create(2000),
				Strings.create("label"), Strings.create("ghost")));
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withLoadedPaths(loads)
			.build();
		String all = allContent(result);
		assertTrue(all.contains("[Skill: ghost — w/skills/ghost — unavailable:"), all);
	}

	@Test
	public void testOverBudgetSkillRendersVisibly() {
		// Byte budgets are advisory across providers: do not silently drop a
		// behaviour-changing skill based on an imprecise token proxy.
		AMap<AString, ACell> loads = alphaSkillLoads();
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx, 300)
			.withLoadedPaths(loads)
			.build();
		String all = allContent(result);
		assertTrue(all.contains("[Skill: alpha — w/skills/alpha]"), all);
		assertTrue(all.contains("Do the thing."), all);
	}

	@Test
	public void testDuplicateSkillEntriesRenderOnce() {
		// Same content identity under two RESOLVABLE paths (e.g. loaded at
		// different tiers or via different addresses) → the body renders once.
		AMap<AString, ACell> one = alphaSkillLoads();
		AMap<AString, ACell> entryMeta = (AMap<AString, ACell>)
			one.get(Strings.create("w/skills/alpha"));
		// A byte-identical copy of the skill at a second address — same
		// metadata map, same content identity.
		engine.jobs().invokeOperation("v/ops/covia/copy",
			Maps.of(Strings.create("from"), Strings.create("w/skills/alpha"),
				Strings.create("to"), Strings.create("w/skills/alias")),
			ctx).awaitResult(5000);
		AMap<AString, ACell> both = one.assoc(Strings.create("w/skills/alias"), entryMeta);

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withLoadedPaths(both)
			.build();
		String all = allContent(result);
		// One body, one [Skill:] block — whichever address renders it (map
		// iteration order picks; content identity guarantees they're the same).
		int firstBody = all.indexOf("## Alpha\nDo the thing.");
		assertTrue(firstBody >= 0, all);
		assertEquals(-1, all.indexOf("## Alpha\nDo the thing.", firstBody + 1),
			"duplicate skill content must render once: " + all);
		int firstBlock = all.indexOf("[Skill: ");
		assertEquals(-1, all.indexOf("[Skill: ", firstBlock + 1),
			"one [Skill:] block for one content identity: " + all);
	}

	/**
	 * A skill announces itself and its unload key in its own header. The
	 * separate inventory that used to carry "(skill)" and a byte budget is
	 * gone: it restated the elements rendered above it, and its per-build byte
	 * counts broke the prefix cache for everything that followed.
	 */
	@Test
	public void testSkillElementCarriesNameAndUnloadKey() {
		String all = allContent(new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withLoadedPaths(alphaSkillLoads(), ctx)
			.build());
		assertTrue(all.contains("[Skill: alpha — w/skills/alpha]"), all);
		assertFalse(all.contains("[Context Map]"), all);
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
			.withConfig(config)
			.withSystemPrompt()
			.withContextEntries()
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
			.withConfig(null)
			.withSystemPrompt()
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
			.withConfig(null)
			.withSystemPrompt()
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
			.withConfig(null)
			.withSystemPrompt()
			.withInboxMessages(inbox)
			.withTools()
			.build();

		ACell last = result.history().get(result.history().count() - 1);
		assertEquals("user", RT.ensureString(RT.getIn(last, K_ROLE)).toString());
		assertEquals("Hello agent", RT.ensureString(RT.getIn(last, K_CONTENT)).toString());
	}

	@Test
	public void testInboxMapMessageWithExternalProvenance() {
		AVector<ACell> inbox = Vectors.of(
			(ACell) Maps.of(
				Fields.CALLER, Strings.create("did:key:z6MkBob"),
				Fields.MESSAGE, Strings.create("Please review the report")));

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
			.withInboxMessages(inbox)
			.withTools()
			.build();

		AVector<ACell> history = result.history();
		ACell last = history.get(history.count() - 1);
		String content = RT.ensureString(RT.getIn(last, K_CONTENT)).toString();
		assertEquals("Please review the report", content, "the user's text is never touched");
		// Attribution is a venue-authored SYSTEM message right before the foreign turn
		ACell before = history.get(history.count() - 2);
		assertEquals("system", RT.getIn(before, K_ROLE).toString());
		String note = RT.ensureString(RT.getIn(before, K_CONTENT)).toString();
		assertTrue(note.startsWith("Venue attribution:") && note.contains("did:key:z6MkBob"), note);
	}

	@Test
	public void testRenderMessageOmitsCurrentCallerAttribution() {
		AMap<AString, ACell> envelope = Maps.of(
			Fields.CALLER, ALICE_DID,
			Fields.MESSAGE, Strings.create("This is me"));

		AMap<AString, ACell> rendered = ContextBuilder.renderMessageForContext(
			envelope, Strings.intern("user"), ALICE_DID);

		assertEquals("user", RT.getIn(rendered, K_ROLE).toString());
		assertEquals("This is me", RT.getIn(rendered, K_CONTENT).toString());
	}

	@Test
	public void testRenderMessageAttributesDifferentCallerConsistently() {
		AString bob = Strings.create("did:key:z6MkBob");
		AMap<AString, ACell> envelope = Maps.of(
			Fields.CALLER, bob,
			Fields.MESSAGE, Strings.create("Please review the report"));
		AMap<AString, ACell> storedTurn = Maps.of(
			K_ROLE, Strings.intern("user"),
			K_CONTENT, Strings.create("Please review the report"),
			Fields.CALLER, bob);

		AMap<AString, ACell> live = ContextBuilder.renderMessageForContext(
			envelope, Strings.intern("user"), ALICE_DID);
		AMap<AString, ACell> persisted = ContextBuilder.renderMessageForContext(
			storedTurn, null, ALICE_DID);

		assertEquals(live, persisted);
		assertEquals("Please review the report", RT.getIn(live, K_CONTENT).toString(),
			"attribution is no longer spliced into user text (it is a system message from the builder)");
	}


	@Test
	public void testAttributionNoteOncePerPrincipalChange() {
		AString bob = Strings.create("did:key:z6MkBob");
		AVector<ACell> inbox = Vectors.of(
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("first from bob")),
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("second from bob")),
			(ACell) Maps.of(Fields.CALLER, ALICE_DID, Fields.MESSAGE, Strings.create("alice herself")),
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("bob again")));
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null).withSystemPrompt().withInboxMessages(inbox).withTools().build();
		AVector<ACell> h = result.history();
		int notes = 0, bobNotes = 0, ownNotes = 0;
		for (long i = 0; i < h.count(); i++) {
			ACell m = h.get(i);
			if (!"system".equals(RT.getIn(m, K_ROLE).toString())) continue;
			String c = RT.ensureString(RT.getIn(m, K_CONTENT)).toString();
			if (!c.startsWith("Venue attribution:")) continue;
			notes++;
			if (c.contains("z6MkBob")) bobNotes++;
			if (c.contains("your own principal")) ownNotes++;
		}
		assertEquals(3, notes, "one note per change of submitting principal: bob, back to alice, bob again");
		assertEquals(2, bobNotes);
		assertEquals(1, ownNotes);
	}


	@Test
	public void testAttributionNotesSpeakToTheRelationship() {
		// The agent's own transition context: a sub-principal of Alice
		RequestContext agentCtx = RequestContext.ofAuthority(covia.grid.Authority.ofAgent(ALICE_DID, Strings.create("helper")));
		ContextBuilder b = new ContextBuilder(engine, agentCtx).withConfig(null);

		String owner = b.attributionNote(ALICE_DID);
		assertTrue(owner.contains("from your owner") && owner.contains("with confidence"), owner);

		String sibling = b.attributionNote(covia.grid.Principals.agentDID(ALICE_DID, Strings.create("scout")));
		assertTrue(sibling.contains("scout") && sibling.contains("colleague"), sibling);

		String venue = b.attributionNote(engine.getDIDString());
		assertTrue(venue.contains("the venue itself") && venue.contains("trusted operator"), venue);

		String stranger = b.attributionNote(Strings.create(engine.getDIDString() + ":public"));
		assertTrue(stranger.contains("anonymous public principal") && stranger.contains("untrusted"), stranger);

		String other = b.attributionNote(Strings.create("did:key:z6MkBob"));
		assertTrue(other.contains("another user of this venue") && other.contains("no authority beyond"), other);

		// A public-owned agent (open dev venue) hears its owner addressed as its owner, not as a stranger
		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		ContextBuilder pb = new ContextBuilder(engine,
			RequestContext.ofAuthority(covia.grid.Authority.ofAgent(publicDID, Strings.create("Assistant")))).withConfig(null);
		String publicOwner = pb.attributionNote(publicDID);
		assertTrue(publicOwner.contains("from your owner") && publicOwner.contains("with confidence"), publicOwner);

		// Every note says it is the venue's verified word, not the sender's text
		for (String n : new String[] {owner, sibling, venue, stranger, other, publicOwner}) {
			assertTrue(n.startsWith("Venue attribution:") && n.contains("Verified by the venue"), n);
		}
	}

	// ========== Empty state signal ==========

	@Test
	public void testEmptyStateSignalWhenNoInput() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
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
			.withConfig(null)
			.withSystemPrompt()
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
		// per-engine cache returns the cached AVector instance. Default tools
		// are opt-in (#92), so the config must request them.
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ContextBuilder.ContextResult r1 = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();
		ContextBuilder.ContextResult r2 = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
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
		// Default tools are opt-in (#92) — request them via config.
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();

		assertTrue(result.tools().count() > 0, "Should have default tools");
		assertTrue(result.configToolMap().size() > 0, "Should have tool mappings");
		// Verify a known default tool is present
		assertTrue(result.configToolMap().containsKey("covia_read"));
	}

	@Test
	public void testDefaultToolPackIsMinimalReadOnly() {
		// The default pack is deliberately minimal: read-only situational
		// awareness only. Capability tools (writes, agent lifecycle, assets,
		// grid, schema) arrive via skills or the explicit config tools
		// allowlist — this drift-guards against the pack silently regrowing.
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();

		assertEquals(java.util.Set.of("covia_read", "covia_list"),
			result.configToolMap().keySet(),
			"default pack must stay minimal and read-only — add tools via skills instead");
	}

	@Test
	public void testSystemPromptIncludesSessionContext() {
		// Venue name in the system prompt; the current date rides a TAIL
		// message (withCurrentDate) so the cacheable prefix stays stable.
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
			.withCurrentDate()
			.build();
		AString sys = extractSystemContent(result.history());
		assertNotNull(sys);
		String content = sys.toString();
		assertTrue(content.contains("Venue:"),
			"System prompt should include venue name: " + content);
		// CACHE GUARD: no changing values in the system prompt — the date
		// must never creep back into the head of the cached prefix.
		assertFalse(content.contains(java.time.LocalDate.now().toString()),
			"system prompt must not contain the current date (cache buster): " + content);
		// The date is present, as the last message.
		AString tail = RT.ensureString(RT.getIn(
			result.history().get(result.history().count() - 1), K_CONTENT));
		assertTrue(tail.toString().contains(java.time.LocalDate.now().toString()),
			"the date rides the tail message: " + tail);
	}

	/**
	 * The loads budget is SILENT until it matters. A per-turn inventory used to
	 * print here; it restated the loaded elements rendered immediately above
	 * and its byte counts broke the prefix cache for everything after it.
	 */
	@Test
	public void testBudgetWarningIsSilentWhenQuiet() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
			.withBudgetWarning()
			.withTools().build();
		assertFalse(allContent(result).contains("Context budget"),
			"no budget message below the warning threshold");
	}

	/** Each loaded element carries its own unload key, so no inventory is needed. */
	@Test
	public void testLoadedElementsCarryTheirUnloadKey() {
		writeSkill("w/skills/keyed", "Body of keyed");
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/skills/keyed"),
			Maps.of(Skills.K_SKILL, CVMBool.TRUE,
				Strings.create("budget"), CVMLong.create(2000),
				Strings.create("label"), Strings.create("keyed")));

		String all = allContent(new ContextBuilder(engine, ctx)
			.withConfig(null).withLoadedPaths(loads, ctx).build());
		// The path is what context_unload takes, so it rides in the header.
		assertTrue(all.contains("[Skill: keyed — w/skills/keyed]"), all);
		assertFalse(all.contains("[Context Map]"), all);
	}

	@Test
	public void testSystemPromptIncludesModelWhenConfigured() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("model"), Strings.create("gpt-4.1-mini"));
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
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
			.withConfig(null)
			.withSystemPrompt()
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
			.withConfig(config)
			.withSystemPrompt()
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
			.withConfig(config)
			.withSystemPrompt()
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
			.withConfig(null)
			.withSystemPrompt()
			.build();
		AString defaultSys = extractSystemContent(defaultResult.history());
		assertNotNull(defaultSys);
		assertTrue(defaultSys.toString().contains("Covia Lattice"),
			"Default system prompt should include lattice reference");
		assertTrue(defaultSys.toString().contains("Workspace"),
			"Default system prompt should describe workspace namespace");
		assertTrue(defaultSys.toString().contains("v/ops"),
			"Default system prompt should mention v/ops catalog");
		assertTrue(defaultSys.toString().contains("Only claim or use capabilities backed by tools"),
			"Namespace reference must not imply that addressability grants a capability");

		// Custom identity prompt → lattice reference STILL appended
		AMap<AString, ACell> customConfig = Maps.of(
			Strings.intern("systemPrompt"),
			Strings.create("You are Carol the AP Approver. Be concise."));
		ContextBuilder.ContextResult customResult = new ContextBuilder(engine, ctx)
			.withConfig(customConfig)
			.withSystemPrompt()
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
		// covia_read lives in the default pack, which is opt-in (#92).
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();

		boolean foundCoviaRead = false;
		for (long i = 0; i < result.tools().count(); i++) {
			AMap<AString, ACell> tool = RT.castMap(result.tools().get(i));
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
			assertTrue(descStr.contains("Read a value"),
				"Description body should still be present");
		}
		assertTrue(foundCoviaRead, "covia_read tool should be in the default tool list");
	}

	@Test
	public void testDefaultToolsDisabled() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("defaultTools"), CVMBool.FALSE);

		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
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
			.withConfig(config)
			.withSystemPrompt()
			.withTools()
			.build();

		assertNotNull(result.caps());
		assertEquals(1, result.caps().count());
		assertNotNull(result.capsCtx());
	}

	@Test
	public void testNoCapsUnrestricted() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(null)
			.withSystemPrompt()
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

		builder.withConfig(null)
			.withSystemPrompt();

		assertTrue(builder.getConsumed() > 0, "System prompt should consume budget");
		assertTrue(builder.getRemaining() < 100_000);
		assertEquals(100_000, builder.getConsumed() + builder.getRemaining());
	}

	@Test
	public void testBudgetInResult() {
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx, 200_000)
			.withConfig(null)
			.withSystemPrompt()
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

		// defaultTools: true so the build carries the default pack (#92) and the
		// "tools present" assertion below remains meaningful.
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx)
			.withConfig(config)
			.withSystemPrompt()
			.withContextEntries()
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
			.withConfig(null)
			.withSystemPrompt()
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
		builder.withConfig(null)
			.withSystemPrompt()
			.withLoadedPaths(loads)
			.withTools()
			.build();
	}

	@Test public void testNonSkillLoadShowsItsPath() {
		// A non-skill load already labelled itself with its ref, which IS the
		// unload key — the inventory was a second copy of that.
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create("w/data"),
				Strings.create("value"), Strings.create("payload-here")), ctx).awaitResult(5000);
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/data"), Maps.of(
				Strings.create("budget"), CVMLong.create(800),
				Strings.create("label"), Strings.create("Test Data")));

		String all = allContent(new ContextBuilder(engine, ctx)
			.withConfig(null).withLoadedPaths(loads, ctx).build());
		assertTrue(all.contains("w/data"), all);
		assertTrue(all.contains("payload-here"), all);
		assertFalse(all.contains("[Context Map]"), all);
	}

	@Test public void testBudgetWarningAt70Pct() {
		// Inflate the system prompt to push budget usage past 70%.
		// defaultTools=false suppresses the lattice reference so the message
		// size is dominated by the configured systemPrompt.
		AMap<AString, ACell> bigConfig = Maps.of(
			Strings.intern("systemPrompt"), Strings.create("x".repeat(800)),
			Strings.intern("defaultTools"), CVMBool.FALSE);
		ContextBuilder.ContextResult result = new ContextBuilder(engine, ctx, 1000)
			.withConfig(bigConfig)
			.withSystemPrompt()
			.withBudgetWarning()
			.build();

		String all = allContent(result);
		assertTrue(all.contains("Context budget"), "should warn when budget > 70%: " + all);
		assertTrue(all.contains("unload"), all);
	}

	@Test public void testSafetyValveNoPruneBelow90() {
		ContextBuilder builder = new ContextBuilder(engine, ctx); // 180k budget
		builder.withConfig(null).withSystemPrompt();

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/a"), Maps.of(Strings.create("budget"), CVMLong.create(500)));

		AMap<AString, ACell> result = builder.applySafetyValve(loads);
		assertEquals(1, result.count(), "Should not prune below 90%");
	}

	@Test public void testSafetyValveIsAdvisoryAbove90Percent() {
		// Inflate the system prompt past 90% of budget; defaultTools=false
		// suppresses the lattice reference so size is dominated by systemPrompt.
		AMap<AString, ACell> bigConfig = Maps.of(
			Strings.intern("systemPrompt"), Strings.create("x".repeat(950)),
			Strings.intern("defaultTools"), CVMBool.FALSE);
		ContextBuilder builder = new ContextBuilder(engine, ctx, 1000);
		builder.withConfig(bigConfig).withSystemPrompt();
		// Consumed is now > 90% of 1000

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/old"), Maps.of(
				Strings.create("budget"), CVMLong.create(10),
				Strings.create("ts"), CVMLong.create(1000)),
			Strings.create("w/new"), Maps.of(
				Strings.create("budget"), CVMLong.create(10),
				Strings.create("ts"), CVMLong.create(2000)));

		AMap<AString, ACell> result = builder.applySafetyValve(loads);
		assertEquals(loads, result, "Advisory accounting must not silently remove loads");
	}

	@Test public void testJobTemporaryPathLoadsThroughVirtualNamespace() {
		Job scope = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of("scope", "temp-load"), ctx);
		scope.awaitResult(5000);
		RequestContext jobCtx = ctx.withJobId(scope.getID());
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "t/live-note", "value", "TEMP_LOAD_VISIBLE"),
			jobCtx).awaitResult(5000);

		AMap<AString, ACell> loads = Maps.of(
			Strings.create("t/live-note"), Maps.of("budget", 500L));
		String rendered = allContent(new ContextBuilder(engine, jobCtx)
			.withLoadedPaths(loads).build());
		assertTrue(rendered.contains("TEMP_LOAD_VISIBLE"), rendered);
	}
}

package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

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
import covia.adapter.agent.ContextAssembler.Prompt;
import covia.adapter.agent.ContextAssembler.Spec;
import covia.api.Fields;
import covia.grid.Job;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.TestEngine;

/**
 * Loads entries beyond a path key — text, op and job sources (AGENT_CONTEXT.md
 * §6.2 at the loads tiers), their placement (volatile entries in the tail),
 * load-order rendering, and the kind-agnostic tools/skills/skillsets rule.
 */
public class LoadsTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_BUDGET = Strings.intern("budget");
	private static final AString K_TS     = Strings.intern("ts");
	private static final AString K_LABEL  = Strings.intern("label");

	@BeforeEach
	public void setup(TestInfo info) {
		did = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(did);
	}

	// ========== helpers ==========

	private void write(String path, ACell value) {
		try {
			engine.jobs().invokeInternal("v/ops/covia/write",
				Maps.of(Fields.PATH, Strings.create(path), Fields.VALUE, value), ctx).get();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static String content(ACell message) {
		AString c = RT.ensureString(RT.getIn(message, "content"));
		return (c != null) ? c.toString() : "";
	}

	private static String all(AVector<ACell> messages) {
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < messages.count(); i++) sb.append(content(messages.get(i))).append('\n');
		return sb.toString();
	}

	private static AMap<AString, ACell> spec(Object... kvs) {
		AMap<AString, ACell> m = Maps.of(K_BUDGET, CVMLong.create(500));
		for (int i = 0; i < kvs.length; i += 2) {
			ACell v = (kvs[i + 1] instanceof ACell c) ? c : Strings.create(String.valueOf(kvs[i + 1]));
			m = m.assoc(Strings.create((String) kvs[i]), v);
		}
		return m;
	}

	// ========== declaredLoads: the entry forms ==========

	@Test
	public void testDeclaredLoadsAcceptEveryEntryForm() {
		AMap<AString, ACell> loads = ContextChain.declaredLoads(Maps.of(
			Strings.create("w/notes"), Maps.of(K_BUDGET, CVMLong.create(200)),
			Strings.create("brief"), spec("text", "Be brief."),
			Strings.create("listing"), spec("op", "v/ops/covia/list", "input", Maps.of(Fields.PATH, Strings.create("w"))),
			Strings.create("result"), spec("job", "00ff"),
			Strings.create("alias"), spec("ref", "w/other")), "loads", true);
		assertEquals(5, loads.count());
		// Every entry is normalised the same way — budget clamped, ts stamped — whatever its form.
		for (var e : loads.entrySet()) {
			assertNotNull(RT.getIn(e.getValue(), "ts"), e.getKey().toString());
			assertNotNull(RT.getIn(e.getValue(), "budget"), e.getKey().toString());
		}
	}

	@Test
	public void testDeclaredLoadsRejectAmbiguousOrMalformedForms() {
		assertThrows(IllegalArgumentException.class, () -> ContextChain.declaredLoads(Maps.of(
			Strings.create("x"), spec("text", "note", "op", "v/ops/covia/list")), "loads"));
		assertThrows(IllegalArgumentException.class, () -> ContextChain.declaredLoads(Maps.of(
			Strings.create("x"), spec("input", Maps.of())), "loads"));
		assertThrows(IllegalArgumentException.class, () -> ContextChain.declaredLoads(Maps.of(
			Strings.create("x"), spec("op", CVMLong.create(1))), "loads"));
		assertThrows(IllegalArgumentException.class, () -> ContextChain.declaredLoads(Maps.of(
			Strings.create("x"), spec("volatile", "yes")), "loads"));
	}

	// ========== ordering ==========

	@Test
	public void testOrderedIsLoadOrderUndatedFirst() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("z-late"), spec("ts", CVMLong.create(300)),
			Strings.create("a-early"), spec("ts", CVMLong.create(100)),
			Strings.create("m-mid"), spec("ts", CVMLong.create(200)),
			Strings.create("pinned-b"), spec(),
			Strings.create("pinned-a"), spec());
		List<String> keys = Loads.ordered(loads).stream().map(e -> e.getKey().toString()).toList();
		assertEquals(List.of("pinned-a", "pinned-b", "a-early", "m-mid", "z-late"), keys);
	}

	@Test
	public void testElementsRenderInLoadOrder() {
		write("w/first", Strings.create("FIRST"));
		write("w/second", Strings.create("SECOND"));
		// Keys chosen so hash/key order would disagree with load order.
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/second"), spec("ts", CVMLong.create(1)),
			Strings.create("w/first"), spec("ts", CVMLong.create(2)));
		String rendered = all(Loads.elements(engine, ctx, loads, Labels.BRACKET));
		assertTrue(rendered.indexOf("SECOND") < rendered.indexOf("FIRST"), rendered);
	}

	// ========== entry forms at a loads tier ==========

	@Test
	public void testTextEntryRendersUnderItsKey() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("brief"), spec("text", "Answer in one line."));
		AVector<ACell> elements = Loads.elements(engine, ctx, loads, Labels.BRACKET);
		assertEquals(1, elements.count());
		String text = content(elements.get(0));
		assertTrue(text.startsWith("[Context: brief]"), "the header is the unload key: " + text);
		assertTrue(text.contains("Answer in one line."), text);
		// A declared label replaces the key in the header.
		AMap<AString, ACell> labelled = Maps.of(Strings.create("brief"), spec("text", "x", "label", "House rules"));
		assertTrue(content(Loads.elements(engine, ctx, labelled, Labels.BRACKET).get(0)).startsWith("[Context: House rules]"));
	}

	@Test
	public void testOpEntryIsVolatileByDefault() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("ping"), Strings.create("pong"))));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(0, snap.elements().count(), "an op entry leaves the live surface");
		assertEquals(1, snap.volatileElements().count());
		String text = content(snap.volatileElements().get(0));
		assertTrue(text.startsWith("[Context: echoed]"), text);
		assertTrue(text.contains("pong"), text);
		assertEquals("tail", RT.getIn(snap.diagnostics().get(0), "band").toString());

		// volatile: false pins an op result into the live surface instead.
		AMap<AString, ACell> pinned = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(), "volatile", CVMBool.FALSE));
		Loads.Snapshot live = Loads.resolve(engine, ctx, pinned, java.util.Set.of(), Labels.BRACKET);
		assertEquals(1, live.elements().count());
		assertEquals(0, live.volatileElements().count());
	}

	@Test
	public void testPathEntryCanBeDeclaredVolatile() {
		write("w/ticker", Strings.create("42"));
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/ticker"), spec("volatile", CVMBool.TRUE));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(0, snap.elements().count());
		assertTrue(content(snap.volatileElements().get(0)).contains("42"));
	}

	@Test
	public void testJobEntryRendersACompletedJobsOutput() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("answer"), CVMLong.create(7)), ctx);
		job.awaitResult(5000);
		AMap<AString, ACell> loads = Maps.of(Strings.create("earlier"),
			spec("job", job.getID().toHexString(), "path", "answer"));
		AVector<ACell> elements = Loads.elements(engine, ctx, loads, Labels.BRACKET);
		assertEquals(1, elements.count());
		String text = content(elements.get(0));
		assertTrue(text.startsWith("[Context: earlier]"), text);
		assertTrue(text.contains("7"), text);
	}

	@Test
	public void testAbsentAndFailingEntriesKeepTheContract() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/nothing-here"), spec(),
			Strings.create("broken"), spec("op", "v/ops/no/such/op"));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(0, snap.elements().count(), "absent → skipped quietly");
		assertEquals(1, snap.volatileElements().count(), "an erroring op entry is visible");
		assertTrue(content(snap.volatileElements().get(0)).contains("unavailable"));
		for (long i = 0; i < snap.diagnostics().count(); i++) {
			ACell d = snap.diagnostics().get(i);
			String status = RT.getIn(d, "status").toString();
			String ref = RT.getIn(d, "ref").toString();
			assertEquals(ref.equals("broken") ? "unavailable" : "absent", status, d.toString());
		}
	}

	// ========== placement in the assembled prompt ==========

	@Test
	public void testVolatileLoadsRenderAfterTheConversationAndBeforeTheNotices() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("brief"), spec("text", "STABLE-NOTE"),
			Strings.create("fresh"), spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("k"), Strings.create("FRESH-RESULT"))));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		AVector<ACell> loop = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "c1", "name", "covia_read", "arguments", "{}"))),
			(ACell) Maps.of("role", "tool", "id", "c1", "name", "covia_read", "content", "x"));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, null, null, null,
			null, null, Vectors.of((ACell) Strings.create("hi")), true, loop, null, null, null, null)
			.withLoads(snap, Vectors.empty(), loads);
		Prompt p = ContextAssembler.assemble(s);
		AVector<ACell> m = p.messages();
		// head, [Context: brief], "hi", assistant, tool, [Context: fresh], notices
		assertEquals(7, m.count(), all(m));
		assertTrue(content(m.get(1)).contains("STABLE-NOTE"), "stable entry in the live surface");
		assertTrue(content(m.get(5)).contains("FRESH-RESULT"), "volatile entry after the tool loop");
		assertTrue(content(m.get(6)).contains("Current date:"), "notices still last");
		// The volatile element sits beyond every cache mark, so it busts only itself.
		assertEquals(2, p.marks().get(ContextAssembler.Band.LIVE));
		assertEquals(3, p.marks().get(ContextAssembler.Band.CONVERSATION));
		assertEquals(5, p.marks().get(ContextAssembler.Band.TOOL_LOOP));
		assertEquals(Vectors.of(CVMLong.create(2), CVMLong.create(4)), p.cacheMarks());
	}

	// ========== the kind-agnostic contribution rule ==========

	@Test
	public void testAnyLoadsEntryMayContributeSkillSourcesAndTools() {
		write("w/team-skills/review", Maps.of(
			Strings.create("description"), Strings.create("Review code the house way")));
		write("w/session-brief", Strings.create("Session profile."));
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/session-brief"), spec(
			"skillsets", Vectors.of(Strings.create("w/team-skills")),
			"tools", Vectors.of(Strings.create("v/test/ops/echo"))));
		Skills.SkillSources sources = Skills.effectiveSources(null, loads);
		assertEquals(Vectors.of(Strings.create("w/team-skills")), sources.skillsets(),
			"a plain load's skillsets widen discovery like a skill's");
		List<Skills.SkillIndexEntry> index = Skills.listSkills(engine, ctx, sources);
		assertEquals(1, index.size());
		assertEquals("review", index.get(0).name());

		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(1, snap.tools().count(), "a plain load's tools join the palette");
		assertEquals("load", RT.getIn(snap.toolProvenance().get(0), "source").toString());
	}

	@Test
	public void testLoadContributedToolsFollowLoadOrder() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("later"), spec("text", "b", "ts", CVMLong.create(2),
				"tools", Vectors.of(Strings.create("v/ops/covia/read"))),
			Strings.create("earlier"), spec("text", "a", "ts", CVMLong.create(1),
				"tools", Vectors.of(Strings.create("v/test/ops/echo"))));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(2, snap.tools().count());
		assertEquals("test_echo", RT.getIn(snap.tools().get(0), "name").toString());
		assertEquals("covia_read", RT.getIn(snap.tools().get(1), "name").toString());
	}

	// ========== context_load: the same forms from the agent ==========

	private HarnessTools.LoadScope scope() {
		return new HarnessTools.LoadScope(engine, ctx, Maps.empty(), Maps.empty(), true, "unavailable", null);
	}

	@Test
	public void testContextLoadAcceptsTextOpAndJobUnderAnId() {
		HarnessTools.LoadScope scope = scope();
		ACell noted = HarnessTools.contextLoad(Maps.of(
			Strings.create("id"), Strings.create("brief"),
			Strings.create("text"), Strings.create("Keep it short.")), scope);
		assertEquals("brief", RT.getIn(noted, "path").toString());
		assertEquals(CVMBool.TRUE, RT.getIn(noted, "loaded"));
		assertEquals("Keep it short.", RT.getIn(scope.loads, "brief", "text").toString());
		assertFalse(Loads.isVolatile(scope.loads.get(Strings.create("brief"))));

		ACell fresh = HarnessTools.contextLoad(Maps.of(
			Strings.create("id"), Strings.create("listing"),
			Strings.create("op"), Strings.create("v/ops/covia/list"),
			Strings.create("input"), Maps.of(Fields.PATH, Strings.create("w"))), scope);
		assertTrue(RT.getIn(fresh, "note").toString().contains("never cached"), fresh.toString());
		assertTrue(Loads.isVolatile(scope.loads.get(Strings.create("listing"))));
		assertEquals("v/ops/covia/list", RT.getIn(scope.loads, "listing", "op").toString());

		// Explicit placement wins over the op default.
		HarnessTools.contextLoad(Maps.of(
			Strings.create("id"), Strings.create("pinned-listing"),
			Strings.create("op"), Strings.create("v/ops/covia/list"),
			Strings.create("volatile"), CVMBool.FALSE), scope);
		assertFalse(Loads.isVolatile(scope.loads.get(Strings.create("pinned-listing"))));

		// The stored specs are what a declared tier would hold — they render the same way.
		AVector<ACell> live = Loads.elements(engine, ctx, scope.loads, Labels.BRACKET);
		assertTrue(all(live).contains("[Context: brief]"), all(live));
	}

	@Test
	public void testContextLoadRejectsAmbiguousOrKeylessCalls() {
		HarnessTools.LoadScope scope = scope();
		String none = HarnessTools.contextLoad(Maps.empty(), scope).toString();
		assertTrue(none.startsWith("Error: path is required"), none);
		String two = HarnessTools.contextLoad(Maps.of(
			Strings.create("path"), Strings.create("w/x"), Strings.create("text"), Strings.create("y")), scope).toString();
		assertTrue(two.startsWith("Error: give exactly one of"), two);
		String noId = HarnessTools.contextLoad(Maps.of(Strings.create("text"), Strings.create("y")), scope).toString();
		assertTrue(noId.startsWith("Error: id is required"), noId);
		assertEquals(0, scope.loads.count(), "nothing stored on a rejected call");

		// The path form is unchanged: the path is its own key.
		write("w/rules", Strings.create("rule"));
		ACell loaded = HarnessTools.contextLoad(Maps.of(Strings.create("path"), Strings.create("w/rules")), scope);
		assertEquals("w/rules", RT.getIn(loaded, "path").toString());
		ACell unloaded = HarnessTools.contextUnload(Maps.of(Strings.create("path"), Strings.create("w/rules")), scope);
		assertEquals(CVMBool.TRUE, RT.getIn(unloaded, "unloaded"));
	}

	// ========== session tier: a skill pre-loaded at mint ==========

	@Test
	public void testSessionMintLoadsPreloadASkill() {
		engine.jobs().invokeOperation("v/ops/agent/create",
			Maps.of(Fields.AGENT_ID, "preloaded-agent",
				Fields.CONFIG, Maps.of(
					Fields.OPERATION, "v/ops/llmagent/chat",
					"llmOperation", "v/test/ops/llm",
					"systemPrompt", "You are preloaded.")),
			ctx).awaitResult(5000);
		ACell chat = engine.jobs().invokeOperation("v/ops/agent/chat",
			Maps.of(Fields.AGENT_ID, "preloaded-agent", Fields.MESSAGE, "hello",
				Fields.LOADS, Maps.of(Strings.create("v/skills/data/lattice"),
					Maps.of(Strings.create("skill"), CVMBool.TRUE, K_BUDGET, CVMLong.create(2000)))),
			ctx).awaitResult(15000);
		AString sessionId = RT.ensureString(RT.getIn(chat, "sessionId"));
		assertNotNull(sessionId, chat.toString());

		ACell inspected = engine.jobs().invokeOperation("v/ops/agent/context",
			Maps.of(Fields.AGENT_ID, "preloaded-agent", Fields.SESSION_ID, sessionId,
				Fields.MESSAGE, "what next?"),
			ctx).awaitResult(15000);
		String messages = convex.core.util.JSON.print(RT.getIn(inspected, Fields.MESSAGES)).toString();
		assertTrue(messages.contains("[Skill: lattice"), "the skill is loaded for this session: " + messages);
		AVector<ACell> loads = RT.ensureVector(RT.getIn(inspected, "loads"));
		assertNotNull(loads, inspected.toString());
		assertEquals("skill", RT.getIn(loads.get(0), "kind").toString());
	}
}

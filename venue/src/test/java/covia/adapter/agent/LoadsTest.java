package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
 * §6.2 at the loads tiers) — rendered as tool exchanges that name their
 * provenance; their placement (volatile entries in the tail); load-order
 * rendering; and the kind-agnostic tools/skills/skillsets rule.
 */
public class LoadsTest {

	private final Engine engine = TestEngine.ENGINE;
	private AString did;
	private RequestContext ctx;

	private static final AString K_BUDGET = Strings.intern("budget");
	private static final AString K_TS     = Strings.intern("ts");

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

	/** The i-th exchange's call arguments (exchanges are assistant/tool pairs). */
	private static ACell args(AVector<ACell> exchanges, int i) {
		return RT.getIn(exchanges.get(2L * i), "toolCalls", 0, "arguments");
	}

	/** The i-th exchange's result content. */
	private static String result(AVector<ACell> exchanges, int i) {
		return content(exchanges.get(2L * i + 1));
	}

	private static AMap<AString, ACell> spec(Object... kvs) {
		AMap<AString, ACell> m = Maps.of(K_BUDGET, CVMLong.create(500));
		for (int i = 0; i < kvs.length; i += 2) {
			ACell v = (kvs[i + 1] instanceof ACell c) ? c : Strings.create(String.valueOf(kvs[i + 1]));
			m = m.assoc(Strings.create((String) kvs[i]), v);
		}
		return m;
	}

	private Loads.Snapshot resolve(AMap<AString, ACell> loads) {
		return Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
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
	public void testExchangesRenderInLoadOrder() {
		write("w/first", Strings.create("FIRST"));
		write("w/second", Strings.create("SECOND"));
		// Keys chosen so hash/key order would disagree with load order.
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/second"), spec("ts", CVMLong.create(1)),
			Strings.create("w/first"), spec("ts", CVMLong.create(2)));
		AVector<ACell> exchanges = resolve(loads).exchanges();
		assertEquals("SECOND", result(exchanges, 0));
		assertEquals("FIRST", result(exchanges, 1));
	}

	// ========== the exchange: data, with its provenance ==========

	@Test
	public void testPathLoadIsAnExchangeNamingItsSource() {
		write("w/notes", Strings.create("note body"));
		AVector<ACell> exchanges = resolve(Maps.of(Strings.create("w/notes"), spec())).exchanges();
		assertEquals(2, exchanges.count(), "one call, one result");
		assertEquals("assistant", RT.getIn(exchanges.get(0), "role").toString());
		assertEquals("loaded_context", RT.getIn(exchanges.get(0), "toolCalls", 0, "name").toString());
		assertEquals("w/notes", RT.getIn(args(exchanges, 0), "key").toString());
		assertEquals("w/notes", RT.getIn(args(exchanges, 0), "ref").toString());
		assertEquals("config.loads", RT.getIn(args(exchanges, 0), "from").toString(), "undated → agent tier");
		assertEquals("tool", RT.getIn(exchanges.get(1), "role").toString());
		assertEquals("note body", result(exchanges, 0), "bare content — no header, the call carries the key");
		assertEquals(RT.getIn(exchanges.get(0), "toolCalls", 0, "id"), RT.getIn(exchanges.get(1), "id"));

		// A dated entry was loaded in-session.
		AVector<ACell> loaded = resolve(Maps.of(Strings.create("w/notes"), spec("ts", CVMLong.create(5)))).exchanges();
		assertEquals("loaded", RT.getIn(args(loaded, 0), "from").toString());
	}

	@Test
	public void testTextEntryCarriesItsKeyAndLabel() {
		AVector<ACell> exchanges = resolve(Maps.of(Strings.create("brief"), spec("text", "Answer in one line."))).exchanges();
		assertEquals("brief", RT.getIn(args(exchanges, 0), "key").toString());
		assertEquals("text", RT.getIn(args(exchanges, 0), "source").toString());
		assertNull(RT.getIn(args(exchanges, 0), "label"), "no label declared, the key is enough");
		assertEquals("Answer in one line.", result(exchanges, 0));
		// A declared label rides in the call too.
		AVector<ACell> labelled = resolve(Maps.of(Strings.create("brief"), spec("text", "x", "label", "House rules"))).exchanges();
		assertEquals("House rules", RT.getIn(args(labelled, 0), "label").toString());
	}

	@Test
	public void testOpEntryIsVolatileByDefault() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("ping"), Strings.create("pong"))));
		Loads.Snapshot snap = resolve(loads);
		assertEquals(0, snap.exchanges().count(), "an op entry leaves the live surface");
		assertEquals(2, snap.volatileExchanges().count());
		ACell a = args(snap.volatileExchanges(), 0);
		assertEquals("v/test/ops/echo", RT.getIn(a, "op").toString(), "the call names the operation");
		assertEquals("pong", RT.getIn(a, "input", "ping").toString(), "and its input");
		assertTrue(result(snap.volatileExchanges(), 0).contains("pong"));
		assertEquals("tail", RT.getIn(snap.diagnostics().get(0), "band").toString());

		// volatile: false pins an op result into the live surface instead.
		AMap<AString, ACell> pinned = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(), "volatile", CVMBool.FALSE));
		Loads.Snapshot live = resolve(pinned);
		assertEquals(2, live.exchanges().count());
		assertEquals(0, live.volatileExchanges().count());
	}

	@Test
	public void testPathEntryCanBeDeclaredVolatile() {
		write("w/ticker", Strings.create("42"));
		Loads.Snapshot snap = resolve(Maps.of(Strings.create("w/ticker"), spec("volatile", CVMBool.TRUE)));
		assertEquals(0, snap.exchanges().count());
		assertEquals("42", result(snap.volatileExchanges(), 0));
	}

	@Test
	public void testJobEntryRendersACompletedJobsOutput() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("answer"), CVMLong.create(7)), ctx);
		job.awaitResult(5000);
		AVector<ACell> exchanges = resolve(Maps.of(Strings.create("earlier"),
			spec("job", job.getID().toHexString(), "path", "answer"))).exchanges();
		assertEquals(job.getID().toHexString(), RT.getIn(args(exchanges, 0), "job").toString());
		assertEquals("answer", RT.getIn(args(exchanges, 0), "path").toString());
		assertTrue(result(exchanges, 0).contains("7"));
	}

	@Test
	public void testAbsentAndFailingEntriesKeepTheContract() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/nothing-here"), spec(),
			Strings.create("broken"), spec("op", "v/ops/no/such/op"));
		Loads.Snapshot snap = resolve(loads);
		assertEquals(0, snap.exchanges().count(), "absent → skipped quietly, no exchange at all");
		assertEquals(2, snap.volatileExchanges().count(), "an erroring op entry is visible");
		assertTrue(result(snap.volatileExchanges(), 0).startsWith("Error: "), "as a tool error");
		assertTrue(result(snap.volatileExchanges(), 0).contains("unavailable"));
		assertEquals(CVMBool.TRUE, RT.getIn(snap.volatileExchanges().get(1), "isError"));
		for (long i = 0; i < snap.diagnostics().count(); i++) {
			ACell d = snap.diagnostics().get(i);
			String status = RT.getIn(d, "status").toString();
			String ref = RT.getIn(d, "ref").toString();
			assertEquals(ref.equals("broken") ? "unavailable" : "absent", status, d.toString());
		}
	}

	@Test
	public void testVolatileEntryRendersWithinItsBudgetWhateverItsShape() {
		String longText = "x".repeat(3000);
		// A verbatim string in the live surface stays verbatim (the renderValue contract)…
		AMap<AString, ACell> live = Maps.of(Strings.create("note"),
			spec("text", longText, "budget", CVMLong.create(600)));
		assertEquals(longText, result(resolve(live).exchanges(), 0));
		// …but the same entry declared volatile is cut at its budget with a visible trailer.
		AMap<AString, ACell> tail = Maps.of(Strings.create("note"),
			spec("text", longText, "budget", CVMLong.create(600), "volatile", CVMBool.TRUE));
		Loads.Snapshot snap = resolve(tail);
		String text = result(snap.volatileExchanges(), 0);
		assertFalse(text.contains(longText), "not verbatim");
		assertTrue(text.contains("more bytes beyond this entry's budget of 600"), text);
		assertTrue(text.contains("reload it with a larger budget, or fetch the value with a tool"), text);
		assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 600 + 200, "budget plus trailer: " + text.length());
		assertEquals(CVMBool.TRUE, RT.getIn(snap.diagnostics().get(0), "truncated"));

		// A structured op result is already bounded by the explorer, whose own
		// annotation is the hint — no trailer is added on top of it.
		AMap<AString, ACell> opEntry = Maps.of(Strings.create("listing"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("blob"), Strings.create("y".repeat(3000))),
				"budget", CVMLong.create(300)));
		String opText = result(resolve(opEntry).volatileExchanges(), 0);
		assertTrue(opText.contains("/* String, 3.0KB */"), opText);
		assertFalse(opText.contains("more bytes beyond"), opText);

		// A short volatile entry is untouched, and the call half carries no content to cut.
		AMap<AString, ACell> small = Maps.of(Strings.create("note"), spec("text", "short", "volatile", CVMBool.TRUE));
		Loads.Snapshot fits = resolve(small);
		assertEquals("short", result(fits.volatileExchanges(), 0));
		assertEquals(CVMBool.FALSE, RT.getIn(fits.diagnostics().get(0), "truncated"));
	}

	// ========== placement in the assembled prompt ==========

	@Test
	public void testVolatileLoadsRenderAfterTheConversationAndBeforeTheNotices() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("brief"), spec("text", "STABLE-NOTE"),
			Strings.create("fresh"), spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("k"), Strings.create("FRESH-RESULT"))));
		Loads.Snapshot snap = resolve(loads);
		AVector<ACell> loop = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "c1", "name", "covia_read", "arguments", "{}"))),
			(ACell) Maps.of("role", "tool", "id", "c1", "name", "covia_read", "content", "x"));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, null, null, null,
			null, null, Vectors.of((ACell) Strings.create("hi")), true, loop, null, null, null, null)
			.withLoads(snap, Vectors.empty(), loads);
		Prompt p = ContextAssembler.assemble(s);
		AVector<ACell> m = p.messages();
		// head, marker, brief call, brief result, "hi", assistant, tool, fresh call, fresh result, notices
		assertEquals(10, m.count(), all(m));
		assertEquals("user", RT.getIn(m.get(1), "role").toString());
		assertEquals(ContextAssembler.LOAD_CONTEXT_REQUEST, content(m.get(1)), "the request precedes the live exchanges");
		assertEquals("STABLE-NOTE", content(m.get(3)), "stable entry in the live surface");
		assertEquals("hi", content(m.get(4)));
		assertTrue(content(m.get(8)).contains("FRESH-RESULT"), "volatile exchange after the tool loop");
		assertTrue(content(m.get(9)).contains("Current date:"), "notices still last");
		// The volatile exchange sits beyond every cache mark, so it busts only itself.
		assertEquals(4, p.marks().get(ContextAssembler.Band.LIVE));
		assertEquals(5, p.marks().get(ContextAssembler.Band.CONVERSATION));
		assertEquals(7, p.marks().get(ContextAssembler.Band.TOOL_LOOP));
		assertEquals(Vectors.of(CVMLong.create(4), CVMLong.create(6)), p.cacheMarks());
	}

	@Test
	public void testNoMarkerWithoutLiveExchanges() {
		// A volatile-only load follows the input, which is already a user turn: no marker.
		AMap<AString, ACell> loads = Maps.of(Strings.create("fresh"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("k"), Strings.create("v"))));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, null, null, null,
			null, null, Vectors.of((ACell) Strings.create("hi")), true, null, null, null, null, null)
			.withLoads(resolve(loads), Vectors.empty(), loads);
		AVector<ACell> m = ContextAssembler.assemble(s).messages();
		// head, "hi", fresh call, fresh result, notices
		assertEquals(5, m.count(), all(m));
		assertEquals("hi", content(m.get(1)));
		assertFalse(all(m).contains(ContextAssembler.LOAD_CONTEXT_REQUEST));
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

		Loads.Snapshot snap = resolve(loads);
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
		Loads.Snapshot snap = resolve(loads);
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
		AVector<ACell> live = resolve(scope.loads).exchanges();
		assertTrue(all(live).contains("Keep it short."), all(live));
		assertEquals("loaded", RT.getIn(args(live, 0), "from").toString(), "an agent load is dated");
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

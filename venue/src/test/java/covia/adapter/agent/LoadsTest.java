package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * provenance; watched entries append-only-on-change placement; load-order
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
		return convex.core.util.JSON.toString(messages);
	}

	/** The i-th resolved aggregate-ready entry. */
	private static AMap<AString, ACell> entry(AVector<ACell> entries, int i) {
		return RT.ensureMap(entries.get(i));
	}

	private static String result(AVector<ACell> entries, int i) {
		return content(entry(entries, i));
	}

	private static AVector<ACell> aggregate(AVector<ACell> entries) {
		return ContextAssembler.contextExchanges(entries);
	}

	/** Exact provider messages for one watched candidate. */
	private static AVector<ACell> observationMessages(Loads.Snapshot snapshot, int i) {
		return RT.ensureVector(RT.getIn(snapshot.observations().get(i), "value"));
	}

	/** Aggregate-ready data recovered from a watched candidate's tool result. */
	private static AVector<ACell> observationEntries(Loads.Snapshot snapshot, int i) {
		AVector<ACell> messages = observationMessages(snapshot, i);
		for (long j = 0; j < messages.count(); j++) {
			AVector<ACell> entries = RT.ensureVector(RT.getIn(messages.get(j), "structuredContent"));
			if (entries != null) return entries;
		}
		return Vectors.empty();
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
		AVector<ACell> entries = resolve(Maps.of(Strings.create("w/notes"), spec())).exchanges();
		assertEquals(1, entries.count());
		AVector<ACell> exchange = aggregate(entries);
		assertEquals(2, exchange.count(), "one aggregate call, one result");
		assertEquals("assistant", RT.getIn(exchange.get(0), "role").toString());
		assertEquals(ContextAssembler.PINNED_CONTEXT_TOOL,
			RT.getIn(exchange.get(0), "toolCalls", 0, "name").toString());
		assertEquals(Maps.empty(), RT.getIn(exchange.get(0), "toolCalls", 0, "arguments"),
			"synthetic arguments contain no duplicate metadata or content");
		ACell item = RT.getIn(exchange.get(1), "structuredContent", 0);
		assertEquals("w/notes", RT.getIn(item, "ref").toString());
		assertNull(RT.getIn(item, "key"), "pinned_context exposes no unload handles");
		assertNull(RT.getIn(item, "id"), "a default path is not repeated as an id");
		assertEquals("note body", RT.getIn(item, "content").toString());
		assertEquals(RT.getIn(exchange.get(0), "toolCalls", 0, "id"), RT.getIn(exchange.get(1), "id"));

		// A dated entry was loaded in-session.
		AVector<ACell> loaded = aggregate(resolve(Maps.of(
			Strings.create("w/notes"), spec("ts", CVMLong.create(5)))).exchanges());
		assertEquals(ContextAssembler.LOADED_CONTEXT_TOOL,
			RT.getIn(loaded.get(0), "toolCalls", 0, "name").toString());
		assertEquals("note body", RT.getIn(loaded.get(1), "structuredContent", "w/notes", "content").toString());
	}

	@Test
	public void testTextEntryCarriesItsKeyAndLabel() {
		AVector<ACell> entries = resolve(Maps.of(Strings.create("brief"), spec("text", "Answer in one line."))).exchanges();
		assertEquals("brief", RT.getIn(entry(entries, 0), "key").toString());
		assertEquals("text", RT.getIn(entry(entries, 0), "source").toString());
		assertNull(RT.getIn(entry(entries, 0), "label"), "no label declared, the key is enough");
		assertEquals("Answer in one line.", result(entries, 0));
		// A declared label rides in the result entry too.
		AVector<ACell> labelled = resolve(Maps.of(Strings.create("brief"), spec("text", "x", "label", "House rules"))).exchanges();
		assertEquals("House rules", RT.getIn(entry(labelled, 0), "label").toString());
	}

	@Test
	public void testOperatorLoadDefaultsToInstructionWithoutDuplicatingData() {
		AMap<AString, ACell> loads = ContextChain.operatorLoads(Maps.of(
			Strings.create("brief"), spec("text", "Answer in one line.", "label", "Response policy")),
			"config.loads");
		Loads.Snapshot snap = resolve(loads);
		assertEquals(1, snap.instructionElements().count());
		assertEquals("system", RT.getIn(snap.instructionElements().get(0), "role").toString());
		assertEquals(Labels.render(Labels.BRACKET, Labels.Kind.PINNED_CONTEXT,
			"Answer in one line.", "Response policy"),
			RT.getIn(snap.instructionElements().get(0), "content").toString());
		assertEquals(0, snap.exchanges().count(), "trusted content appears only in the system message");
	}

	@Test
	public void testCallerAndAgentDataCannotPromoteThemselves() {
		AMap<AString, ACell> caller = ContextChain.declaredLoads(Maps.of(
			Strings.create("note"), spec("text", "caller data", "trusted", CVMBool.TRUE)),
			"loads", true);
		Loads.Snapshot callerSnap = resolve(caller);
		assertEquals(0, callerSnap.instructionElements().count());
		assertEquals("caller data", result(callerSnap.exchanges(), 0));

		AMap<AString, ACell> agentMeta = AbstractLLMAdapter.buildLoadEntryMeta(500, null)
			.assoc(Loads.K_TEXT, Strings.create("agent data"))
			.assoc(Loads.K_TRUSTED, CVMBool.TRUE);
		Loads.Snapshot agentSnap = resolve(Maps.of(Strings.create("note"), agentMeta));
		assertEquals(0, agentSnap.instructionElements().count(),
			"runtime-owned entries must not honour a forged trust marker");
		assertEquals("agent data", result(agentSnap.exchanges(), 0));
	}

	@Test
	public void testOpEntryIsVolatileByDefault() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("ping"), Strings.create("pong"))));
		Loads.Snapshot snap = resolve(loads);
		assertEquals(0, snap.exchanges().count(), "an op entry leaves the live surface");
		assertEquals(1, snap.observations().count());
		AVector<ACell> observed = observationEntries(snap, 0);
		ACell a = entry(observed, 0);
		assertEquals("v/test/ops/echo", RT.getIn(a, "op").toString(), "the call names the operation");
		assertEquals("pong", RT.getIn(a, "input", "ping").toString(), "and its input");
		assertTrue(result(observed, 0).contains("pong"));
		assertEquals("observation", RT.getIn(snap.diagnostics().get(0), "band").toString());

		// volatile: false pins an op result into the live surface instead.
		AMap<AString, ACell> pinned = Maps.of(Strings.create("echoed"),
			spec("op", "v/test/ops/echo", "input", Maps.of(), "volatile", CVMBool.FALSE));
		Loads.Snapshot live = resolve(pinned);
		assertEquals(1, live.exchanges().count());
		assertEquals(0, live.observations().count());
	}

	@Test
	public void testContextOperationMustDeclareReadOnly() {
		String target = "w/volatile-op-must-not-write";
		AMap<AString, ACell> loads = Maps.of(Strings.create("bad-watcher"),
			spec("op", "v/ops/covia/write", "input", Maps.of(
				Fields.PATH, Strings.create(target), Fields.VALUE, CVMBool.TRUE)));
		Loads.Snapshot snapshot = resolve(loads);
		String error = RT.getIn(observationEntries(snapshot, 0).get(0), Fields.ERROR).toString();
		assertTrue(error.contains("operation.readOnly=true"), error);
		assertNull(engine.resolvePath(Strings.create(target), ctx),
			"context observation must never execute a mutating operation");
	}

	@Test
	public void testPathEntryCanBeDeclaredVolatile() {
		write("w/ticker", Strings.create("42"));
		Loads.Snapshot snap = resolve(Maps.of(Strings.create("w/ticker"), spec("volatile", CVMBool.TRUE)));
		assertEquals(0, snap.exchanges().count());
		assertEquals("42", result(observationEntries(snap, 0), 0));
	}

	@Test
	public void testVolatileSkillUsesTheSameObservationLifecycle() {
		AString path = Strings.create("w/watched-skill");
		write(path.toString(), Maps.of(
			"description", "Watched skill",
			"content", Maps.of("inline", "WATCHED-SKILL-ONE")));
		AMap<AString, ACell> loads = Maps.of(path, Maps.of(
			"skill", CVMBool.TRUE,
			"volatile", CVMBool.TRUE,
			"budget", CVMLong.create(2000)));
		Loads.Snapshot first = resolve(loads);
		assertEquals(0, first.instructionElements().count(),
			"a watched skill is not baked into the rendered prefix");
		assertEquals(1, first.observations().count());
		assertTrue(all(RT.ensureVector(RT.getIn(first.observations().get(0), "value")))
			.contains("WATCHED-SKILL-ONE"));

		AMap<AString, ACell> frame = GoalTreeContext.applyObservations(
			GoalTreeContext.createFrame(""), first.observations(), 1L);
		write(path.toString(), Maps.of(
			"description", "Watched skill",
			"content", Maps.of("inline", "WATCHED-SKILL-TWO")));
		Loads.Snapshot second = resolve(loads);
		AMap<AString, ACell> changed = GoalTreeContext.applyObservations(
			frame, second.observations(), 2L);
		String history = all(RT.ensureVector(changed.get(GoalTreeContext.K_CONVERSATION)));
		assertTrue(history.contains("WATCHED-SKILL-ONE"), history);
		assertTrue(history.contains("WATCHED-SKILL-TWO"), history);
	}

	@Test
	public void testJobEntryRendersACompletedJobsOutput() {
		Job job = engine.jobs().invokeOperation("v/test/ops/echo",
			Maps.of(Strings.create("answer"), CVMLong.create(7)), ctx);
		job.awaitResult(5000);
		AVector<ACell> exchanges = resolve(Maps.of(Strings.create("earlier"),
			spec("job", job.getID().toHexString(), "path", "answer"))).exchanges();
		assertEquals(job.getID().toHexString(), RT.getIn(entry(exchanges, 0), "job").toString());
		assertEquals("answer", RT.getIn(entry(exchanges, 0), "path").toString());
		assertTrue(result(exchanges, 0).contains("7"));
	}

	@Test
	public void testAbsentAndFailingEntriesKeepTheContract() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("w/nothing-here"), spec(),
			Strings.create("broken"), spec("op", "v/ops/no/such/op"));
		Loads.Snapshot snap = resolve(loads);
		assertEquals(1, snap.exchanges().count(), "pinned absence remains visible");
		assertEquals(CVMBool.TRUE, RT.getIn(entry(snap.exchanges(), 0), "absent"));
		assertEquals(1, snap.observations().count(), "an erroring op entry is visible");
		String error = RT.getIn(entry(observationEntries(snap, 0), 0), Fields.ERROR).toString();
		assertFalse(error.isBlank(), "the per-entry failure remains visible");
		assertNull(RT.getIn(observationMessages(snap, 0).get(1), "isError"),
			"one failed item does not mark the whole aggregate result failed");
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
		AMap<AString, ACell> watched = Maps.of(Strings.create("note"),
			spec("text", longText, "budget", CVMLong.create(600), "volatile", CVMBool.TRUE));
		Loads.Snapshot snap = resolve(watched);
		String text = result(observationEntries(snap, 0), 0);
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
		Loads.Snapshot opSnapshot = resolve(opEntry);
		String opText = result(observationEntries(opSnapshot, 0), 0);
		assertTrue(opText.contains("/* String, 3.0KB */"), opText);
		assertFalse(opText.contains("more bytes beyond"), opText);

		// A short volatile entry is untouched, and the call half carries no content to cut.
		AMap<AString, ACell> small = Maps.of(Strings.create("note"), spec("text", "short", "volatile", CVMBool.TRUE));
		Loads.Snapshot fits = resolve(small);
		assertEquals("short", result(observationEntries(fits, 0), 0));
		assertEquals(CVMBool.FALSE, RT.getIn(fits.diagnostics().get(0), "truncated"));
	}

	// ========== placement in the assembled prompt ==========

	@Test
	public void testVolatileLoadsAppendToConversationOnlyWhenChanged() {
		AMap<AString, ACell> loads = Maps.of(
			Strings.create("brief"), spec("text", "STABLE-NOTE"),
			Strings.create("fresh"), spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("k"), Strings.create("FRESH-RESULT"))));
		Loads.Snapshot snap = resolve(loads);
		AVector<ACell> loop = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "c1", "name", "covia_read", "arguments", "{}"))),
			(ACell) Maps.of("role", "tool", "id", "c1", "name", "covia_read", "content", "x"));
		AMap<AString, ACell> frame = GoalTreeContext.appendTurn(
			GoalTreeContext.createFrame(""), Maps.of("role", "user", "content", "hi"));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, true,
			Vectors.empty(), Vectors.empty(), Vectors.empty(), loads,
			Vectors.of((ACell) frame), Vectors.empty(), Vectors.empty(), true,
			loop, null, Vectors.empty(), null, null)
			.withLoads(snap, Vectors.empty(), loads);
		frame = GoalTreeContext.applyObservations(frame,
			ContextAssembler.observations(s, snap), 1L);
		s = s.withFrames(Vectors.of((ACell) frame));
		Prompt p = ContextAssembler.assemble(s);
		AVector<ACell> m = p.messages();
		// head, stable marker/call/result, "hi", watched call/result, tool loop, notices
		assertEquals(10, m.count(), all(m));
		assertEquals("user", RT.getIn(m.get(1), "role").toString());
		assertEquals(ContextAssembler.LOAD_CONTEXT_REQUEST, content(m.get(1)), "the request precedes the live exchanges");
		assertEquals("STABLE-NOTE", RT.getIn(m.get(3), "structuredContent", 0, "content").toString(),
			"stable entry in the live surface");
		assertEquals("hi", content(m.get(4)));
		assertTrue(RT.getIn(m.get(6), "structuredContent", 0, "content").toString().contains("FRESH-RESULT"),
			"watched exchange is durable conversation before the tool loop");
		assertTrue(content(m.get(9)).contains("Current date:"), "notices still last");
		// A stable observation is now part of the append-only cache prefix.
		assertEquals(4, p.marks().get(ContextAssembler.Band.LIVE));
		assertEquals(7, p.marks().get(ContextAssembler.Band.CONVERSATION));
		assertEquals(9, p.marks().get(ContextAssembler.Band.TOOL_LOOP));
		assertEquals(Vectors.of(CVMLong.create(6), CVMLong.create(8)), p.cacheMarks());

		AMap<AString, ACell> unchanged = GoalTreeContext.applyObservations(frame,
			ContextAssembler.observations(s, resolve(loads)), 2L);
		assertSame(frame, unchanged, "same watched value adds no tail or conversation bytes");
	}

	@Test
	public void testNoMarkerWithoutLiveExchanges() {
		// A watched-only load is appended after the user turn: no initial-load marker.
		AMap<AString, ACell> loads = Maps.of(Strings.create("fresh"),
			spec("op", "v/test/ops/echo", "input", Maps.of(Strings.create("k"), Strings.create("v"))));
		Loads.Snapshot snapshot = resolve(loads);
		AMap<AString, ACell> frame = GoalTreeContext.appendTurn(
			GoalTreeContext.createFrame(""), Maps.of("role", "user", "content", "hi"));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, true,
			Vectors.empty(), Vectors.empty(), Vectors.empty(), loads,
			Vectors.of((ACell) frame), Vectors.empty(), Vectors.empty(), true,
			Vectors.empty(), null, Vectors.empty(), null, null)
			.withLoads(snapshot, Vectors.empty(), loads);
		frame = GoalTreeContext.applyObservations(frame,
			ContextAssembler.observations(s, snapshot), 1L);
		s = s.withFrames(Vectors.of((ACell) frame));
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
		assertTrue(RT.getIn(fresh, "note").toString().contains("appends only when changed"), fresh.toString());
		assertTrue(Loads.isVolatile(scope.loads.get(Strings.create("listing"))));
		assertEquals("v/ops/covia/list", RT.getIn(scope.loads, "listing", "op").toString());

		// Explicit placement wins over the op default.
		HarnessTools.contextLoad(Maps.of(
			Strings.create("id"), Strings.create("pinned-listing"),
			Strings.create("op"), Strings.create("v/ops/covia/list"),
			Strings.create("volatile"), CVMBool.FALSE), scope);
		assertFalse(Loads.isVolatile(scope.loads.get(Strings.create("pinned-listing"))));

		// The stored specs are what a declared tier would hold — they render the same way.
		AVector<ACell> live = aggregate(resolve(scope.loads).exchanges());
		assertTrue(all(live).contains("Keep it short."), all(live));
		assertEquals(ContextAssembler.LOADED_CONTEXT_TOOL,
			RT.getIn(live.get(0), "toolCalls", 0, "name").toString());
	}

	@Test
	public void testPersistentLoadAppendsOnceAndReloadAppendsAgain() {
		write("w/reloadable", Strings.create("VALUE-ONE"));
		HarnessTools.LoadScope scope = scope();
		HarnessTools.contextLoad(Maps.of("path", "w/reloadable"), scope);

		AString key = Strings.create("w/reloadable");
		Loads.Append first = Loads.append(engine, ctx, scope.loads, key, Labels.BRACKET,
			Strings.create("context:call-1"));
		scope.loads = first.loads();
		assertEquals("VALUE-ONE",
			RT.getIn(first.messages().get(1), "structuredContent", "w/reloadable", "content").toString());
		assertEquals("context:call-1", RT.getIn(first.messages().get(0), "toolCalls", 0, "id").toString());

		// Mutation alone neither re-reads nor re-renders a persistent entry.
		write("w/reloadable", Strings.create("VALUE-TWO"));
		Loads.Snapshot unchanged = resolve(scope.loads);
		assertEquals(0, unchanged.instructionElements().count());
		assertEquals(0, unchanged.exchanges().count());
		assertEquals("appended", RT.getIn(unchanged.diagnostics().get(0), "status").toString());

		// Explicit reload replaces the registry entry and appends a newer event;
		// it does not rewrite or duplicate the first event.
		HarnessTools.contextLoad(Maps.of("path", "w/reloadable"), scope);
		Loads.Append second = Loads.append(engine, ctx, scope.loads, key, Labels.BRACKET,
			Strings.create("context:call-2"));
		assertEquals("VALUE-TWO",
			RT.getIn(second.messages().get(1), "structuredContent", "w/reloadable", "content").toString());
		AVector<ACell> history = (AVector<ACell>) first.messages().concat(second.messages());
		assertEquals(first.messages(), history.slice(0, first.messages().count()),
			"reload preserves the old vector as an exact prefix");
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

	@Test
	public void testUnloadManifestStaysStableWhileOwnershipChecksStayAtExecution() {
		write("w/one", Strings.create("one"));
		AMap<AString, ACell> outer = ContextChain.declaredLoads(Maps.of(
			Strings.create("pinned"), spec("text", "operator context")), "config.loads");
		HarnessTools.LoadScope scope = new HarnessTools.LoadScope(
			engine, ctx, Maps.empty(), outer, true, "unavailable", null);
		AVector<ACell> declaredTools = Vectors.of(
			(ACell) HarnessTools.DEF_CONTEXT_LOAD,
			(ACell) HarnessTools.DEF_CONTEXT_UNLOAD);

		assertEquals(2, declaredTools.count(),
			"the fixed manifest does not change with the current load set");

		HarnessTools.contextLoad(Maps.of("path", "w/one"), scope);
		HarnessTools.contextLoad(Maps.of("id", "note", "text", "working note"), scope);

		ACell result = HarnessTools.contextUnload(Maps.of("paths", Vectors.of(
			(ACell) Strings.create("w/one"),
			(ACell) Strings.create("note"),
			(ACell) Strings.create("pinned"),
			(ACell) Strings.create("w/ordinary-read"),
			(ACell) Strings.create("note"))), scope);
		assertEquals(2, RT.ensureVector(RT.getIn(result, "unloaded")).count(), result.toString());
		assertEquals(2, RT.ensureVector(RT.getIn(result, "errors")).count(), result.toString());
		assertEquals(0, scope.loads.count(), "only the two agent-managed entries were removed");
		assertNotNull(scope.outerLoads.get(Strings.create("pinned")), "pinned context remains intact");
		assertEquals(2, declaredTools.count(), "unload does not rewrite the tool vector");
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
		AVector<ACell> messages = RT.ensureVector(RT.getIn(inspected, Fields.MESSAGES));
		Skills.ResolvedSkill lattice = Skills.resolveRef(engine, ctx, Strings.create("v/skills/data/lattice"));
		String expected = content(Skills.renderSkillMessage(Labels.BRACKET, lattice.name(),
			Strings.create("v/skills/data/lattice"), lattice.displayBody(), false));
		boolean visible = false;
		for (long i = 0; i < messages.count(); i++) {
			if (expected.equals(content(messages.get(i)))) visible = true;
		}
		assertTrue(visible, "the caller-pinned skill is loaded for this session");
		AVector<ACell> loads = RT.ensureVector(RT.getIn(inspected, "loads"));
		assertNotNull(loads, inspected.toString());
		assertEquals("skill", RT.getIn(loads.get(0), "kind").toString());
	}
}

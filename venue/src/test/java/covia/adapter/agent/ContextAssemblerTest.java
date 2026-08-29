package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.*;

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
 * The assembler: sections, sequence, budget and the pieces it is built from
 * (ToolPalette, Loads, attribution). AGENT_CONTEXT.md §3 as tests.
 */
public class ContextAssemblerTest {

	private final Engine engine = TestEngine.ENGINE;
	private RequestContext ctx;
	private AString ALICE_DID;

	private static final AString K_ROLE    = Strings.intern("role");
	private static final AString K_CONTENT = Strings.intern("content");
	private static final AString K_CONTEXT = Strings.intern("context");

	@BeforeEach
	public void setup(TestInfo info) {
		ALICE_DID = TestEngine.uniqueDID(info);
		ctx = RequestContext.of(ALICE_DID);
	}

	// ========== Helpers ==========

	/** A Spec with only a config: no tools, loads, frames, input or task. */
	private Spec spec(AMap<AString, ACell> config) {
		return new Spec(engine, ctx, null, config, null, null, 0, null,
			null, null, null, null, null, null, true, null, null, null, null, null);
	}

	private Spec spec(AMap<AString, ACell> config, AVector<ACell> frames, AVector<ACell> pending,
			AVector<ACell> input, boolean hasInput) {
		return new Spec(engine, ctx, null, config, null, null, 0, null,
			null, null, null, frames, pending, input, hasInput, null, null, null, null, null);
	}

	private static String content(ACell message) {
		AString c = RT.ensureString(RT.getIn(message, K_CONTENT));
		return (c != null) ? c.toString() : "";
	}

	private static String role(ACell message) {
		return RT.ensureString(RT.getIn(message, K_ROLE)).toString();
	}

	/** The head: always the first message. */
	private static String head(Prompt p) {
		return content(p.messages().get(0));
	}

	/** The tail notices: always the last message when there is no task. */
	private static String tail(Prompt p) {
		return content(p.messages().get(p.messages().count() - 1));
	}

	private static String allContent(AVector<ACell> messages) {
		StringBuilder sb = new StringBuilder();
		for (long i = 0; i < messages.count(); i++) {
			sb.append(content(messages.get(i))).append('\n');
			ACell structured = RT.getIn(messages.get(i), "structuredContent");
			if (structured != null) sb.append(convex.core.util.JSON.toString(structured)).append('\n');
		}
		return sb.toString();
	}

	private static String allContent(Prompt p) {
		return allContent(p.messages());
	}

	private static String renderedSkill(Skills.ResolvedSkill skill, AString path, boolean agentManaged) {
		return content(Skills.renderSkillMessage(
			Labels.BRACKET, skill.name(), path, skill.displayBody(), agentManaged));
	}

	private void write(String path, ACell value) {
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of(Strings.create("path"), Strings.create(path), Strings.create("value"), value),
			ctx).awaitResult(5000);
	}

	private void writeSkill(String path, String description) {
		write(path, Maps.of(Strings.create("description"), Strings.create(description)));
	}

	// ========== Sequence ==========

	@Test
	public void testSequenceHeadConversationTail() {
		AVector<ACell> inbox = Vectors.of((ACell) Strings.create("Do something"));
		Prompt p = ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE), null, null, inbox, true));
		// head, the input, the tail notices
		assertEquals(3, p.messages().count());
		assertEquals("system", role(p.messages().get(0)));
		assertEquals("user", role(p.messages().get(1)));
		assertEquals("Do something", content(p.messages().get(1)));
		assertEquals("system", role(p.messages().get(2)));
		assertTrue(tail(p).contains("Current date: " + java.time.LocalDate.now()), tail(p));
		// Band marks: the head ends after one message; the conversation after two.
		assertEquals(1, p.marks().get(ContextAssembler.Band.HEAD));
		assertEquals(1, p.marks().get(ContextAssembler.Band.LIVE));
		assertEquals(2, p.marks().get(ContextAssembler.Band.CONVERSATION));
		assertEquals(2, p.marks().get(ContextAssembler.Band.TOOL_LOOP));
		// One cache breakpoint: the input message; the tail is never marked.
		assertEquals(Vectors.of(CVMLong.create(1)), p.cacheMarks());
		assertEquals(Vectors.of(CVMLong.create(1)), RT.getIn(p.toL3Input(null), "cacheMarks"));
	}

	@Test
	public void testTaskIsRenderedLast() {
		ACell task = ContextAssembler.user("[Tasks assigned to you]\n- Task 1: do it");
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, null, null, null,
			null, null, Vectors.of((ACell) Strings.create("hi")), true, null, task, null, null, null);
		Prompt p = ContextAssembler.assemble(s);
		ACell last = p.messages().get(p.messages().count() - 1);
		assertEquals("user", role(last));
		assertTrue(content(last).startsWith("[Tasks assigned to you]"));
		// ...after the notices, which carry the date
		assertTrue(content(p.messages().get(p.messages().count() - 2)).contains("Current date:"));
	}

	@Test
	public void testToolLoopTurnsSitBeforeTheTail() {
		AVector<ACell> loop = Vectors.of(
			(ACell) Maps.of("role", "assistant", "content", "", "toolCalls", Vectors.of(
				Maps.of("id", "c1", "name", "covia_read", "arguments", "{}"))),
			(ACell) Maps.of("role", "tool", "id", "c1", "name", "covia_read", "content", "x"));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null, null, null, null,
			null, null, Vectors.of((ACell) Strings.create("hi")), true, loop, null, null, null, null);
		Prompt p = ContextAssembler.assemble(s);
		assertEquals("assistant", role(p.messages().get(2)));
		assertEquals("tool", role(p.messages().get(3)));
		assertTrue(tail(p).contains("Current date:"));
		assertEquals(2, p.marks().get(ContextAssembler.Band.CONVERSATION));
		assertEquals(4, p.marks().get(ContextAssembler.Band.TOOL_LOOP));
		// Two breakpoints: where the cycle began (the input) and where it stands (the last tool result).
		assertEquals(Vectors.of(CVMLong.create(1), CVMLong.create(3)), p.cacheMarks());
	}

	@Test
	public void testNoCacheMarksWithoutConversation() {
		Prompt p = ContextAssembler.assemble(spec(null));
		assertEquals(0, p.cacheMarks().count(), "head and tail are never marked");
		assertNull(RT.getIn(p.toL3Input(null), "cacheMarks"));
	}

	@Test
	public void testTokenTallyIncludesCacheCounts() {
		CycleRecord.begin();
		CycleRecord.tally(Maps.of(Fields.TOKENS, Maps.of(
			Fields.INPUT, CVMLong.create(100), Fields.OUTPUT, CVMLong.create(10),
			Fields.CACHE_READ, CVMLong.create(80), Fields.CACHE_WRITE, CVMLong.create(20))));
		CycleRecord.tally(Maps.of(Fields.TOKENS, Maps.of(
			Fields.INPUT, CVMLong.create(50), Fields.OUTPUT, CVMLong.create(5))));
		AMap<AString, ACell> totals = CycleRecord.end().tokens();
		assertEquals(CVMLong.create(150), totals.get(Fields.INPUT));
		assertEquals(CVMLong.create(165), totals.get(Fields.TOTAL));
		assertEquals(CVMLong.create(80), totals.get(Fields.CACHE_READ));
		assertEquals(CVMLong.create(20), totals.get(Fields.CACHE_WRITE));

		CycleRecord.begin();
		CycleRecord.tally(Maps.of(Fields.TOKENS, Maps.of(Fields.INPUT, CVMLong.create(1))));
		assertNull(CycleRecord.end().tokens().get(Fields.CACHE_READ), "absent means not measured, never zero");
	}

	// ========== Tool calling off ==========

	/** A Spec for a model that cannot call tools presents none — not the
	 *  palette, not the capability notice, not the skills index. */
	@Test
	public void testToolCallingOffPresentsNothingToCall() {
		AMap<AString, ACell> config = Maps.of(
			"caps", Vectors.of(Maps.of("with", "v/ops/covia", "can", "invoke")));
		AVector<ACell> palette = Vectors.of(Maps.of("name", "covia_read", "parameters", Maps.of("type", "object")));
		Spec on = new Spec(engine, ctx, null, config, null, null, 0, null, true,
			palette, null, null, null, null, null, true, null, null, null, null, null);
		Spec off = new Spec(engine, ctx, null, config, null, null, 0, null, false,
			palette, null, null, null, null, null, true, null, null, null, null, null);
		assertEquals(1, on.tools().count());
		assertEquals(0, off.tools().count(), "the Spec itself holds no tools");
		Prompt p = ContextAssembler.assemble(off);
		assertEquals(0, p.tools().count());
		assertFalse(head(p).contains("Capabilities"), "no notice without tools");
		assertEquals(CVMBool.FALSE, ContextAssembler.report(off).get(Strings.intern("toolCalling")));
		assertNull(ContextAssembler.report(on).get(Strings.intern("toolCalling")), "absent means the norm");
	}

	@Test
	public void testStableBandsCompareAsVectorsWithoutParallelHashes() {
		Spec first = spec(null, null, null,
			Vectors.of((ACell) Maps.of("content", "first")), true);
		Spec second = spec(null, null, null,
			Vectors.of((ACell) Maps.of("content", "second")), true);
		Prompt a = ContextAssembler.assemble(first);
		Prompt b = ContextAssembler.assemble(second);
		assertEquals(a.tools(), b.tools());
		int liveEnd = a.marks().get(ContextAssembler.Band.LIVE);
		assertEquals(a.messages().slice(0, liveEnd), b.messages().slice(0, liveEnd));
		assertNotEquals(a.messages(), b.messages());
		assertNull(ContextAssembler.report(first).get(Strings.intern("prefixHashes")));
	}

	/** The profile chain: provider facet, byModel, then the agent's config.modelProfile, one key deep. */
	@Test
	public void testModelProfileLayersProviderModelAndConfig() {
		AMap<AString, ACell> meta = Maps.of("model", Maps.of(
			"options", Maps.of("labels", "xml", "systemMessages", "single"),
			"budget", Maps.of("bytes", 100L),
			"byModel", Maps.of("tiny", Maps.of("options", Maps.of("toolCalling", CVMBool.FALSE)))));
		AbstractLLMAdapter.ModelProfile provider = AbstractLLMAdapter.ModelProfile.of(
			AbstractLLMAdapter.modelProfile(meta, null, null));
		assertTrue(provider.toolCalling());
		assertEquals(Strings.create("xml"), provider.labels());
		assertEquals(100L, provider.budget());
		AbstractLLMAdapter.ModelProfile tiny = AbstractLLMAdapter.ModelProfile.of(
			AbstractLLMAdapter.modelProfile(meta, Strings.create("tiny"), null));
		assertFalse(tiny.toolCalling(), "the model's entry overrides the provider");
		assertEquals(Strings.create("xml"), tiny.labels(), "what it does not state survives");
		AMap<AString, ACell> config = Maps.of("modelProfile", Maps.of(
			"options", Maps.of("toolCalling", CVMBool.TRUE), "budget", Maps.of("bytes", 50L)));
		AbstractLLMAdapter.ModelProfile agent = AbstractLLMAdapter.ModelProfile.of(
			AbstractLLMAdapter.modelProfile(meta, Strings.create("tiny"), config));
		assertTrue(agent.toolCalling(), "the agent's override wins");
		assertEquals(50L, agent.budget());
		assertEquals(Strings.create("xml"), agent.labels());
	}

	// ========== Head ==========

	@Test
	public void testDefaultIdentityWhenNoSystemPrompt() {
		Prompt p = ContextAssembler.assemble(spec(null));
		assertEquals("system", role(p.messages().get(0)));
		assertTrue(head(p).contains("helpful AI agent"));
		assertFalse(head(p).contains("Covia Lattice"), "namespace literacy is a skill, not head text");
	}

	@Test
	public void testCustomSystemPrompt() {
		Prompt p = ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("systemPrompt"), Strings.create("You are a financial analyst."))));
		assertTrue(head(p).contains("financial analyst"));
		assertFalse(head(p).contains("Covia Lattice"));
	}

	@Test
	public void testHeadCarriesVenueModelAndSessionButNeverTheDate() {
		Spec s = new Spec(engine, ctx, null, Maps.of(Strings.intern("model"), Strings.create("gpt-4.1-mini")),
			ContextAssembler.sessionHex(convex.core.data.Blob.fromHex("00aa00aa00aa00aa00aa00aa00aa00aa")),
			null, 0, null, null, null, null, null, null, null, true, null, null, null, null, null);
		Prompt p = ContextAssembler.assemble(s);
		assertTrue(head(p).contains("Venue:"), head(p));
		assertTrue(head(p).contains("Model: gpt-4.1-mini"), head(p));
		assertTrue(head(p).contains("Session: 00aa00aa00aa00aa00aa00aa00aa00aa"), head(p));
		// CACHE GUARD: the date must never creep into the cached prefix.
		assertFalse(head(p).contains(java.time.LocalDate.now().toString()), head(p));
		assertTrue(tail(p).contains(java.time.LocalDate.now().toString()), tail(p));

		assertEquals("beef0001", ContextAssembler.sessionHex(Strings.create("beef0001")));
		assertNull(ContextAssembler.sessionHex(null));
		assertFalse(head(ContextAssembler.assemble(spec(null))).contains("Session:"));
	}

	@Test
	public void testHeadNoticeIsPartOfTheHead() {
		Spec s = spec(null).forFrame(null, "You are inside a subgoal.");
		assertTrue(head(ContextAssembler.assemble(s)).endsWith("You are inside a subgoal."));
	}

	/** A Spec whose palette holds one tool — enough for the capability notice. */
	private Spec withTools(AMap<AString, ACell> config) {
		AVector<ACell> tools = Vectors.of((ACell) ToolPalette.buildToolDefinition("t", null, null));
		return new Spec(engine, ctx, null, config, null, null, 0, null,
			tools, null, null, null, null, null, true, null, null, null, null, null);
	}

	@Test
	public void testCapabilityNoticeOnlyWhenDeclaredAndTheAgentHasTools() {
		assertFalse(head(ContextAssembler.assemble(withTools(null))).contains("Your capabilities (caps)"));

		AMap<AString, ACell> config = Maps.of(Strings.intern("caps"), Vectors.of(
			(ACell) Maps.of(Strings.intern("with"), Strings.create("w/decisions/"), Strings.intern("can"), Strings.create("crud")),
			(ACell) Maps.of(Strings.intern("with"), Strings.create("w/"), Strings.intern("can"), Strings.create("crud/read"))));
		// Capabilities bound what the agent can do: with no tools, no notice.
		assertFalse(head(ContextAssembler.assemble(spec(config))).contains("Your capabilities (caps)"));
		String h = head(ContextAssembler.assemble(withTools(config)));
		assertTrue(h.contains("Your capabilities (caps)"));
		assertTrue(h.contains("crud on w/decisions/"), h);
		assertTrue(h.contains("crud/read on w/"), h);
		assertTrue(h.contains("Capability denied"));
		assertTrue(h.contains("Retrying the same call does not help"));

		String empty = head(ContextAssembler.assemble(withTools(Maps.of(Strings.intern("caps"), Vectors.empty()))));
		assertTrue(empty.contains("(none)"), "deny-all is stated explicitly");
	}

	/** The lattice reference is a venue skill: in the data family, mirrored into root. */
	@Test
	public void testLatticeReferenceIsASkill() {
		Skills.ResolvedSkill lattice = Skills.resolveRef(engine, ctx, Strings.create("v/skills/data/lattice"));
		assertTrue(lattice.body().contains("## Covia Lattice"), lattice.body());
		assertTrue(lattice.body().contains("only claim or use capabilities backed by tools"),
			"addressability must not imply capability");
		assertTrue(lattice.body().contains("`w/` workspace") && lattice.body().contains("v/ops/"));
		Skills.ResolvedSkill mirrored = Skills.resolveRef(engine, ctx, Strings.create("v/skills/root/lattice"));
		assertEquals(lattice.id(), mirrored.id(), "root mirror is the same skill");
		// Pinned via config.loads, it renders as operator-owned skill instructions.
		AMap<AString, ACell> loads = ContextChain.declaredLoads(Maps.of(
			Strings.create("v/skills/data/lattice"), Maps.of(Strings.create("skill"), CVMBool.TRUE)), "config.loads");
		AVector<ACell> elements = Loads.elements(engine, ctx, loads, Labels.BRACKET);
		assertEquals(1, elements.count());
		assertEquals(renderedSkill(lattice, Strings.create("v/skills/data/lattice"), false),
			content(elements.get(0)));
	}

	// ========== Pinned context ==========

	@Test
	public void testPinnedContextFromConfig() {
		write("w/rules", Strings.create("Rule 1: validate all inputs"));
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Vectors.of((ACell) Strings.create("w/rules")));
		Prompt p = ContextAssembler.assemble(spec(config));
		assertEquals("system", role(p.messages().get(1)));
		assertEquals(Labels.render(Labels.BRACKET, Labels.Kind.PINNED_CONTEXT,
			"Rule 1: validate all inputs", "w/rules"), content(p.messages().get(1)));
		assertEquals(2, p.marks().get(ContextAssembler.Band.LIVE));
		assertFalse(allContent(p).contains(ContextAssembler.PINNED_CONTEXT_TOOL),
			"trusted content is rendered once, not duplicated through a tool result");
	}

	@Test
	public void testOperatorCanKeepPinnedContextAsUntrustedData() {
		String payload = "UNTRUSTED-PAYLOAD-436";
		write("w/reference", Strings.create(payload));
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Vectors.of((ACell) Maps.of(
			"ref", "w/reference", "label", "Reference", "trusted", false)));
		Prompt p = ContextAssembler.assemble(spec(config));
		assertEquals("user", role(p.messages().get(1)));
		assertEquals(ContextAssembler.PINNED_CONTEXT_TOOL,
			RT.getIn(p.messages().get(2), "toolCalls", 0, "name").toString());
		assertEquals(payload,
			RT.getIn(p.messages().get(3), "structuredContent", 0, "content").toString());
		assertFalse(content(p.messages().get(0)).contains(payload));
	}

	@Test
	public void testAbsentPinnedContextRemainsVisibleAtItsDeclaredAuthority() {
		AMap<AString, ACell> trusted = Maps.of(K_CONTEXT, Vectors.of((ACell) Maps.of(
			"ref", "w/missing-trusted", "label", "Health instructions")));
		Prompt trustedPrompt = ContextAssembler.assemble(spec(trusted));
		assertEquals("system", role(trustedPrompt.messages().get(1)));
		assertEquals(Labels.render(Labels.BRACKET, Labels.Kind.PINNED_CONTEXT,
			ContextAssembler.ABSENT_CONTEXT_SIGNAL, "Health instructions"),
			content(trustedPrompt.messages().get(1)));

		AMap<AString, ACell> untrusted = Maps.of(K_CONTEXT, Vectors.of((ACell) Maps.of(
			"ref", "w/missing-data", "label", "Optional data", "trusted", false)));
		Prompt untrustedPrompt = ContextAssembler.assemble(spec(untrusted));
		assertEquals(CVMBool.TRUE,
			RT.getIn(untrustedPrompt.messages().get(3), "structuredContent", 0, "absent"));
		assertEquals("Optional data",
			RT.getIn(untrustedPrompt.messages().get(3), "structuredContent", 0, "label").toString());
	}

	@Test
	public void testPinnedContextTrustMustBeBoolean() {
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Vectors.of((ACell) Maps.of(
			"text", "x", "trusted", "yes")));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
			() -> ContextAssembler.assemble(spec(config)));
		assertTrue(ex.getMessage().contains("config.context[0] trusted"), ex.getMessage());
	}

	@Test
	public void testGetMineShapedContextSeparatesPinnedLoadedAndOrdinaryResults() {
		// GetMine has operator context, a stable `now` operation load, dynamic
		// working context, and ordinary covia_read results in the tool loop. Each
		// category must be visible exactly once and under the right tool name.
		String operatorRules = "Operator rules";
		String domainGuide = "Domain guide";
		String today = "2026-08-28";
		String scratch = "Agent scratch reference";
		String ordinaryRead = "ordinary read result";
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Vectors.of(
			(ACell) Maps.of("text", operatorRules),
			(ACell) Maps.of("text", domainGuide)));
		AMap<AString, ACell> pinned = ContextChain.operatorLoads(Maps.of(
			Strings.create("now"), Maps.of(
				"op", "v/test/ops/echo",
				"input", Maps.of("date", today),
				"volatile", false,
				"trusted", false,
				"budget", 500L)), "config.loads");
		AMap<AString, ACell> dynamic = AbstractLLMAdapter.buildLoadEntryMeta(500, null)
			.assoc(Loads.K_TEXT, Strings.create(scratch));
		AMap<AString, ACell> effective = pinned.assoc(Strings.create("working-note"), dynamic);
		Loads.Snapshot loads = Loads.resolve(engine, ctx, effective, java.util.Set.of(), Labels.BRACKET);

		AVector<ACell> ordinary = Vectors.of(
			(ACell) Maps.of("role", "assistant", "toolCalls", Vectors.of(
				Maps.of("id", "read-1", "name", "covia_read",
					"arguments", Maps.of("path", "w/getmine/records")))),
			(ACell) Maps.of("role", "tool", "id", "read-1", "name", "covia_read",
				"content", ordinaryRead));
		Prompt p = ContextAssembler.assemble(spec(config)
			.withLoads(loads, Vectors.empty(), effective)
			.withToolLoop(ordinary));

		assertEquals("system", role(p.messages().get(1)));
		assertEquals("system", role(p.messages().get(2)));
		assertTrue(content(p.messages().get(1)).contains(operatorRules));
		assertTrue(content(p.messages().get(2)).contains(domainGuide));
		ACell aggregateCall = p.messages().get(4);
		assertEquals(2, RT.ensureVector(RT.getIn(aggregateCall, "toolCalls")).count(),
			"one synthetic assistant turn carries one call per ownership class");
		assertEquals(ContextAssembler.PINNED_CONTEXT_TOOL,
			RT.getIn(aggregateCall, "toolCalls", 0, "name").toString());
		assertEquals(ContextAssembler.LOADED_CONTEXT_TOOL,
			RT.getIn(aggregateCall, "toolCalls", 1, "name").toString());
		assertEquals(Maps.empty(), RT.getIn(aggregateCall, "toolCalls", 0, "arguments"));
		assertEquals(Maps.empty(), RT.getIn(aggregateCall, "toolCalls", 1, "arguments"));

		ACell pinnedResult = p.messages().get(5);
		String pinnedJson = convex.core.util.JSON.toString(RT.getIn(pinnedResult, "structuredContent"));
		assertFalse(pinnedJson.contains(operatorRules) || pinnedJson.contains(domainGuide), pinnedJson);
		assertTrue(pinnedJson.contains("\"id\":\"now\"") && pinnedJson.contains(today), pinnedJson);
		assertFalse(pinnedJson.contains("\"key\":"), "pinned_context must not expose unload keys: " + pinnedJson);
		ACell loadedResult = p.messages().get(6);
		assertEquals(scratch,
			RT.getIn(loadedResult, "structuredContent", "working-note", "content").toString());

		ACell ordinaryCall = null;
		ACell ordinaryResult = null;
		for (long i = 0; i < p.messages().count(); i++) {
			ACell message = p.messages().get(i);
			if ("covia_read".equals(String.valueOf(RT.getIn(message, "toolCalls", 0, "name")))) {
				ordinaryCall = message;
			}
			if ("covia_read".equals(String.valueOf(RT.getIn(message, "name")))) {
				ordinaryResult = message;
			}
		}
		assertNotNull(ordinaryCall, p.messages().toString());
		assertNotNull(ordinaryResult, p.messages().toString());
		assertEquals(ordinaryRead, RT.getIn(ordinaryResult, "content").toString());
		assertFalse(pinnedJson.contains(ordinaryRead));
		assertFalse(convex.core.util.JSON.toString(RT.getIn(loadedResult, "structuredContent"))
			.contains(ordinaryRead));
	}

	@Test
	public void testInvalidConfigContextThrows() {
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Strings.create("not-an-array"));
		RuntimeException ex = assertThrows(RuntimeException.class,
			() -> ContextAssembler.assemble(spec(config)));
		assertTrue(ex.getMessage().contains("config.context"), ex.getMessage());
	}

	@Test
	public void testAbsentContextIsFine() {
		assertDoesNotThrow(() -> ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("systemPrompt"), Strings.create("Be helpful")))));
	}

	@Test
	public void testStructuredEntriesRenderThroughCellExplorer() {
		write("w/structured", Maps.of(Strings.create("name"), Strings.create("Alice"),
			Strings.create("role"), Strings.create("analyst")));
		AMap<AString, ACell> config = Maps.of(K_CONTEXT, Vectors.of((ACell) Strings.create("w/structured")));
		String all = allContent(ContextAssembler.assemble(spec(config)));
		assertTrue(all.contains("Alice") && all.contains("analyst"), all);
	}

	// ========== Skills index ==========

	@Test
	public void testSkillsIndexInjectedAndBudgeted() {
		writeSkill("w/skills/alpha", "Alpha skill");
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("skillsets"), Vectors.of((ACell) Strings.create("w/skills")));
		Prompt p = ContextAssembler.assemble(spec(config));
		String all = allContent(p);
		assertTrue(all.contains("[Skills]"), all);
		assertTrue(all.contains("- alpha — Alpha skill"), all);
		assertTrue(all.contains("advertised skill-loading control"),
			"preamble explains the capability without coupling to an alias");
		assertFalse(all.contains("skill_load"), "no provider-facing alias in durable text");
		assertFalse(all.contains("(loaded)"), all);
		assertTrue(p.used() > ContextAssembler.assemble(spec(null)).used(),
			"the index is charged to the budget");
	}

	@Test
	public void testSkillsIndexLoadedMarker() {
		writeSkill("w/skills/alpha", "Alpha skill");
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("skillsets"), Vectors.of((ACell) Strings.create("w/skills")));
		AMap<AString, ACell> effectiveLoads = Maps.of(Strings.create("w/skills/alpha"), Maps.of(
			Strings.create("skill"), CVMBool.TRUE, Strings.create("budget"), CVMLong.create(2000)));
		Spec s = spec(config).withLoads(Loads.Snapshot.EMPTY, Vectors.empty(), effectiveLoads);
		assertTrue(allContent(ContextAssembler.assemble(s)).contains("- alpha — Alpha skill (loaded)"));
	}

	@Test
	public void testLoadedSkillContributesToSkillsIndex() {
		write("w/root-skill", Maps.of(
			Fields.NAME, Strings.create("root-skill"),
			Fields.DESCRIPTION, Strings.create("Reveals specialists"),
			Skills.K_SKILL, Maps.of(Skills.K_SKILLSETS, Vectors.of(Strings.create("w/specialists")))));
		writeSkill("w/specialists/reviewer", "Review a result");
		Skills.ResolvedSkill root = Skills.resolveRef(engine, ctx, Strings.create("w/root-skill"));
		AMap<AString, ACell> loads = Maps.of(root.path(), Skills.buildSkillLoadMeta(2000, root));

		String all = allContent(ContextAssembler.assemble(
			spec(null).withLoads(Loads.Snapshot.EMPTY, Vectors.empty(), loads)));
		assertTrue(all.contains("- reviewer — Review a result"), all);
		assertTrue(all.contains("may also reveal more skills"), all);
		assertFalse(all.contains("- root-skill —"),
			"a direct-ref bootstrap need not make itself a configured source");
	}

	@Test
	public void testSkillsIndexAbsentOrEmpty() {
		assertFalse(allContent(ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("systemPrompt"), Strings.create("Hi"))))).contains("[Skills]"));
		assertFalse(allContent(ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("skillsets"), Vectors.empty())))).contains("[Skills]"));
		assertFalse(allContent(ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("skillsets"), Vectors.of((ACell) Strings.create("w/no-skills-here"))))))
			.contains("[Skills]"));
	}

	@Test
	public void testInvalidConfigSkillsThrows() {
		RuntimeException e1 = assertThrows(RuntimeException.class, () -> ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("skillsets"), Strings.create("w/skills")))));
		assertTrue(e1.getMessage().contains("config.skills"), e1.getMessage());
		RuntimeException e2 = assertThrows(RuntimeException.class, () -> ContextAssembler.assemble(spec(
			Maps.of(Strings.intern("skillsets"), Vectors.of(CVMLong.create(42))))));
		assertTrue(e2.getMessage().contains("config.skills"), e2.getMessage());
	}

	// ========== Loads ==========

	private AMap<AString, ACell> alphaSkillLoads() {
		write("w/skills/alpha", Maps.of(
			Strings.create("description"), Strings.create("Alpha skill"),
			Strings.create("content"), Maps.of(Strings.create("inline"), Strings.create("## Alpha\nDo the thing.")),
			Strings.create("skill"), Maps.of(Strings.create("context"), Vectors.of(Maps.of(
				Strings.create("text"), Strings.create("Alpha extra context"),
				Strings.create("label"), Strings.create("Alpha notes"))))));
		Skills.ResolvedSkill s = Skills.resolveRef(engine, ctx, Strings.create("w/skills/alpha"));
		return Maps.of(Strings.create("w/skills/alpha"), Skills.buildSkillLoadMeta(2000, s));
	}

	@Test
	public void testSkillEntryRendersBodyAndContext() {
		AMap<AString, ACell> loads = alphaSkillLoads();
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		Skills.ResolvedSkill alpha = Skills.resolveRef(engine, ctx, Strings.create("w/skills/alpha"));
		assertEquals(renderedSkill(alpha, Strings.create("w/skills/alpha"), true),
			content(snap.elements().get(0)));
		// The skill's own context entry is data grouped under the skill's unload key.
		assertEquals(1, snap.exchanges().count());
		ACell entry = snap.exchanges().get(0);
		assertEquals("Alpha notes", RT.getIn(entry, "label").toString());
		assertEquals("w/skills/alpha", RT.getIn(entry, "key").toString(), "unloads with the skill");
		assertEquals("Alpha extra context", RT.getIn(entry, "content").toString());
		AVector<ACell> aggregate = ContextAssembler.contextExchanges(snap.exchanges(), false);
		assertEquals(ContextAssembler.LOADED_CONTEXT_TOOL,
			RT.getIn(aggregate.get(0), "toolCalls", 0, "name").toString());
		assertEquals("Alpha extra context",
			RT.getIn(aggregate.get(1), "structuredContent", "w/skills/alpha", "content").toString());
	}

	@Test
	public void testVanishedSkillRendersVisibly() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/skills/ghost"), Maps.of(
			Strings.create("skill"), CVMBool.TRUE,
			Strings.create("budget"), CVMLong.create(2000),
			Strings.create("label"), Strings.create("ghost")));
		Loads.Snapshot snapshot = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(1, snapshot.instructionElements().count(), "the failed skill remains visible");
		assertEquals("unavailable", RT.getIn(snapshot.diagnostics().get(0), "status").toString());
	}

	@Test
	public void testOverBudgetSkillIsNeverEvicted() {
		// Budgets are advisory: a behaviour-changing skill is never dropped on
		// an imprecise token proxy. The tail warns instead.
		Loads.Snapshot loads = Loads.resolve(engine, ctx, alphaSkillLoads(), java.util.Set.of(), Labels.BRACKET);
		Spec s = new Spec(engine, ctx, null, null, null, null, 300, null,
			null, null, null, null, null, null, true, null, null, null, null, null)
			.withLoads(loads, Vectors.empty(), alphaSkillLoads());
		Prompt p = ContextAssembler.assemble(s);
		Skills.ResolvedSkill alpha = Skills.resolveRef(engine, ctx, Strings.create("w/skills/alpha"));
		String all = allContent(p);
		assertTrue(all.contains(renderedSkill(alpha, Strings.create("w/skills/alpha"), true)), all);
		assertTrue(tail(p).contains("[Context budget]"), tail(p));
	}

	@Test
	public void testDuplicateSkillEntriesRenderOnce() {
		AMap<AString, ACell> one = alphaSkillLoads();
		@SuppressWarnings("unchecked")
		AMap<AString, ACell> entryMeta = (AMap<AString, ACell>) one.get(Strings.create("w/skills/alpha"));
		engine.jobs().invokeOperation("v/ops/covia/copy",
			Maps.of(Strings.create("from"), Strings.create("w/skills/alpha"),
				Strings.create("to"), Strings.create("w/skills/alias")), ctx).awaitResult(5000);
		AMap<AString, ACell> both = one.assoc(Strings.create("w/skills/alias"), entryMeta);

		Loads.Snapshot snapshot = Loads.resolve(engine, ctx, both, java.util.Set.of(), Labels.BRACKET);
		String all = allContent(snapshot.elements());
		int firstBody = all.indexOf("## Alpha\nDo the thing.");
		assertTrue(firstBody >= 0, all);
		assertEquals(-1, all.indexOf("## Alpha\nDo the thing.", firstBody + 1), "one body per content identity");
		assertEquals(1, snapshot.instructionElements().count(), "one element per content identity");
	}

	@Test
	public void testLoadedPathsResolution() {
		write("w/test-data", Maps.of(Strings.create("key"), Strings.create("value")));
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/test-data"),
			Maps.of(Strings.create("budget"), CVMLong.create(500)));
		assertTrue(allContent(Loads.elements(engine, ctx, loads, Labels.BRACKET)).contains("key"));
	}

	@Test
	public void testLoadedPathsMissingSkipped() {
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/nonexistent/path"),
			Maps.of(Strings.create("budget"), CVMLong.create(500)));
		AVector<ACell> absent = Loads.elements(engine, ctx, loads, Labels.BRACKET);
		assertEquals(1, absent.count());
		assertEquals(CVMBool.TRUE, RT.getIn(absent.get(0), "absent"));
	}

	@Test
	public void testNonSkillLoadShowsItsPath() {
		write("w/data", Strings.create("payload-here"));
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/data"), Maps.of(
			Strings.create("budget"), CVMLong.create(800),
			Strings.create("label"), Strings.create("Test Data")));
		AVector<ACell> entries = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET).exchanges();
		assertEquals("w/data", RT.getIn(entries.get(0), "key").toString(), "the internal entry retains its registry key");
		assertEquals("Test Data", RT.getIn(entries.get(0), "label").toString());
		assertEquals("payload-here", RT.getIn(entries.get(0), "content").toString());
	}

	@Test
	public void testJobTemporaryPathLoadsThroughVirtualNamespace() {
		Job scope = engine.jobs().invokeOperation("v/test/ops/echo", Maps.of("scope", "temp-load"), ctx);
		scope.awaitResult(5000);
		RequestContext jobCtx = ctx.withJobId(scope.getID());
		engine.jobs().invokeOperation("v/ops/covia/write",
			Maps.of("path", "t/live-note", "value", "TEMP_LOAD_VISIBLE"), jobCtx).awaitResult(5000);
		AMap<AString, ACell> loads = Maps.of(Strings.create("t/live-note"), Maps.of("budget", 500L));
		assertTrue(allContent(Loads.elements(engine, jobCtx, loads, Labels.BRACKET)).contains("TEMP_LOAD_VISIBLE"));
	}

	@Test
	public void testLoadsSnapshotCarriesToolsAndRoutes() {
		write("w/skills/toolful", Maps.of(
			Strings.create("description"), Strings.create("Has a tool"),
			Strings.create("skill"), Maps.of(Strings.create("tools"),
				Vectors.of((ACell) Strings.create("v/ops/covia/read")))));
		AMap<AString, ACell> loads = Maps.of(Strings.create("w/skills/toolful"), Maps.of(
			Strings.create("skill"), CVMBool.TRUE,
			Strings.create("budget"), CVMLong.create(2000)));
		Loads.Snapshot snap = Loads.resolve(engine, ctx, loads, java.util.Set.of(), Labels.BRACKET);
		assertEquals(1, snap.tools().count());
		assertEquals("v/ops/covia/read", snap.routes().get("covia_read").toString());
		assertEquals("skill", RT.getIn(snap.toolProvenance().get(0), Fields.SOURCE).toString());
		assertEquals("w/skills/toolful", RT.getIn(snap.toolProvenance().get(0), Fields.REF).toString());
		// A name fixed by harness or config is never shadowed by a load.
		assertEquals(0, Loads.resolve(engine, ctx, loads, java.util.Set.of("covia_read"), Labels.BRACKET).tools().count());
		// Explicit load metadata remains an override, including disabling the facet tools.
		AMap<AString, ACell> disabled = Maps.of(Strings.create("w/skills/toolful"), Maps.of(
			Strings.create("skill"), CVMBool.TRUE,
			Strings.create("budget"), CVMLong.create(2000),
			Fields.TOOLS, Vectors.empty()));
		assertEquals(0, Loads.resolve(engine, ctx, disabled,
			java.util.Set.of(), Labels.BRACKET).tools().count());
	}

	@Test
	public void testLoadsSnapshotReportsBudgetAndDeduplication() {
		AVector<ACell> large = Vectors.empty();
		for (int i = 0; i < 100; i++) large = large.conj(Strings.create("value-" + i + "-xxxxxxxx"));
		write("w/large-load", large);
		Loads.Snapshot structured = Loads.resolve(engine, ctx,
			Maps.of("w/large-load", Maps.of("budget", 256L)), java.util.Set.of(), Labels.BRACKET);
		ACell diagnostic = structured.diagnostics().get(0);
		assertEquals("load", RT.getIn(diagnostic, "kind").toString());
		assertEquals("resolved", RT.getIn(diagnostic, "status").toString());
		assertEquals(CVMLong.create(256), RT.getIn(diagnostic, "budget"));
		assertEquals(CVMBool.TRUE, RT.getIn(diagnostic, "truncated"));
		assertEquals(CVMBool.FALSE, RT.getIn(diagnostic, "deduplicated"));
		assertTrue(((CVMLong) RT.getIn(diagnostic, Fields.BYTES)).longValue() > 0);

		AMap<AString, ACell> one = alphaSkillLoads();
		AMap<AString, ACell> meta = RT.ensureMap(one.get(Strings.create("w/skills/alpha")));
		engine.jobs().invokeOperation("v/ops/covia/copy",
			Maps.of("from", "w/skills/alpha", "to", "w/skills/diagnostic-alias"), ctx).awaitResult(5000);
		Loads.Snapshot duplicate = Loads.resolve(engine, ctx,
			one.assoc(Strings.create("w/skills/diagnostic-alias"), meta), java.util.Set.of(), Labels.BRACKET);
		int deduplicated = 0;
		for (long i = 0; i < duplicate.diagnostics().count(); i++) {
			if (CVMBool.TRUE.equals(RT.getIn(duplicate.diagnostics().get(i), "deduplicated"))) deduplicated++;
		}
		assertEquals(1, deduplicated);
	}

	// ========== Conversation ==========

	@Test
	public void testPendingResults() {
		AVector<ACell> pending = Vectors.of((ACell) Maps.of(
			Fields.JOB_ID, Strings.create("abc123"),
			Fields.STATUS, Strings.create("COMPLETE"),
			Fields.OUTPUT, Strings.create("result data")));
		Prompt p = ContextAssembler.assemble(spec(null, null, pending, null, true));
		// A result is data: request, one call, one listing as the tool result
		// — head, request, call, result, notices.
		assertEquals(5, p.messages().count(), allContent(p));
		assertEquals("user", role(p.messages().get(1)));
		assertEquals(ContextAssembler.JOB_RESULTS_REQUEST, content(p.messages().get(1)));
		assertEquals("assistant", role(p.messages().get(2)));
		ACell call = RT.getIn(p.messages().get(2), "toolCalls", 0);
		assertEquals("get_job_results", RT.getIn(call, "name").toString());
		assertEquals(0, RT.ensureMap(RT.getIn(call, "arguments")).count(), "no arguments — the listing is the answer");
		assertEquals("tool", role(p.messages().get(3)));
		assertEquals("job abc123 COMPLETE:\nresult data", content(p.messages().get(3)));
		assertEquals(RT.getIn(call, "id"), RT.getIn(p.messages().get(3), "id"));
		assertNull(RT.getIn(p.messages().get(3), "isError"), "a failed job is data about the job, not a failed fetch");

		// Every job once, with its reason when it did not complete — and a note when none was recorded.
		AVector<ACell> mixed = Vectors.of(
			(ACell) Maps.of(Fields.JOB_ID, Strings.create("def456"), Fields.STATUS, Strings.create("FAILED"),
				Fields.ERROR, Strings.create("upstream returned 503")),
			(ACell) Maps.of(Fields.JOB_ID, Strings.create("ghi789"), Fields.STATUS, Strings.create("CANCELLED")));
		Prompt q = ContextAssembler.assemble(spec(null, null, mixed, null, true));
		assertEquals(5, q.messages().count(), "still one call, one result");
		assertEquals("job def456 FAILED: upstream returned 503\n\njob ghi789 CANCELLED — no reason recorded",
			content(q.messages().get(3)));
	}

	@Test
	public void testNothingToAddAddsNothing() {
		// head + tail only
		assertEquals(2, ContextAssembler.assemble(spec(null)).messages().count());
	}

	@Test
	public void testInboxStringMessage() {
		Prompt p = ContextAssembler.assemble(spec(null, null, null,
			Vectors.of((ACell) Strings.create("Hello agent")), true));
		ACell turn = p.messages().get(1);
		assertEquals("user", role(turn));
		assertEquals("Hello agent", content(turn));
	}

	@Test
	public void testForeignInboxMessageIsAttributed() {
		AVector<ACell> inbox = Vectors.of((ACell) Maps.of(
			Fields.CALLER, Strings.create("did:key:z6MkBob"),
			Fields.MESSAGE, Strings.create("Please review the report")));
		AVector<ACell> m = ContextAssembler.assemble(spec(null, null, null, inbox, true)).messages();
		// head, attribution note, the turn, tail
		assertEquals(4, m.count());
		assertEquals("system", role(m.get(1)));
		assertTrue(content(m.get(1)).startsWith("Turn provenance:") && content(m.get(1)).contains("did:key:z6MkBob"));
		assertEquals("Please review the report", content(m.get(2)), "the user's text is never touched");
	}

	@Test
	public void testToMessageNormalisesEnvelopesAndTurnsAlike() {
		AString bob = Strings.create("did:key:z6MkBob");
		AMap<AString, ACell> envelope = Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("Please review"));
		AMap<AString, ACell> stored = Maps.of(K_ROLE, Strings.intern("user"),
			K_CONTENT, Strings.create("Please review"), Fields.CALLER, bob);
		AMap<AString, ACell> live = ConversationRenderer.toMessage(envelope, Strings.intern("user"));
		AMap<AString, ACell> persisted = ConversationRenderer.toMessage(stored, null);
		assertEquals(live, persisted);
		assertEquals("Please review", content(live));
		assertNull(live.get(Fields.CALLER), "framework metadata is dropped");

		AMap<AString, ACell> own = Maps.of(Fields.CALLER, ALICE_DID, Fields.MESSAGE, Strings.create("This is me"));
		assertEquals("This is me", content(ConversationRenderer.toMessage(own, Strings.intern("user"))));
	}

	@Test
	public void testAttributionNoteOncePerPrincipalChange() {
		AString bob = Strings.create("did:key:z6MkBob");
		AVector<ACell> inbox = Vectors.of(
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("first from bob")),
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("second from bob")),
			(ACell) Maps.of(Fields.CALLER, ALICE_DID, Fields.MESSAGE, Strings.create("alice herself")),
			(ACell) Maps.of(Fields.CALLER, bob, Fields.MESSAGE, Strings.create("bob again")));
		AVector<ACell> m = ContextAssembler.assemble(spec(null, null, null, inbox, true)).messages();
		int notes = 0, bobNotes = 0, ownNotes = 0;
		for (long i = 0; i < m.count(); i++) {
			if (!"system".equals(role(m.get(i)))) continue;
			String c = content(m.get(i));
			if (!c.startsWith("Turn provenance:")) continue;
			notes++;
			if (c.contains("z6MkBob")) bobNotes++;
			if (c.contains("relationship=self")) ownNotes++;
		}
		assertEquals(3, notes, "one note per change of submitting principal");
		assertEquals(2, bobNotes);
		assertEquals(1, ownNotes);
	}

	@Test
	public void testAttributionNotesIdentifyRelationshipsWithoutAuthorityInstructions() {
		RequestContext agentCtx = RequestContext.ofAuthority(
			covia.grid.Authority.ofAgent(ALICE_DID, Strings.create("helper")));
		String owner = ContextAssembler.attributionNote(engine, agentCtx, ALICE_DID);
		assertTrue(owner.contains("relationship=owner"), owner);
		String sibling = ContextAssembler.attributionNote(engine, agentCtx,
			covia.grid.Principals.agentDID(ALICE_DID, Strings.create("scout")));
		assertTrue(sibling.contains("relationship=same-owner-agent:scout"), sibling);
		String venue = ContextAssembler.attributionNote(engine, agentCtx, engine.getDIDString());
		assertTrue(venue.contains("relationship=venue"), venue);
		String stranger = ContextAssembler.attributionNote(engine, agentCtx,
			Strings.create(engine.getDIDString() + ":public"));
		assertTrue(stranger.contains("relationship=public-principal")
			&& stranger.contains("authentication=anonymous"), stranger);
		String other = ContextAssembler.attributionNote(engine, agentCtx, Strings.create("did:key:z6MkBob"));
		assertTrue(other.contains("relationship=other-principal"), other);

		AString publicDID = Strings.create(engine.getDIDString() + ":public");
		RequestContext publicCtx = RequestContext.ofAuthority(
			covia.grid.Authority.ofAgent(publicDID, Strings.create("Assistant")));
		String publicOwner = ContextAssembler.attributionNote(engine, publicCtx, publicDID);
		assertTrue(publicOwner.contains("relationship=owner-public-principal"), publicOwner);

		for (String n : new String[] {owner, sibling, venue, stranger, other, publicOwner}) {
			assertTrue(n.startsWith("Turn provenance:")
				&& n.endsWith("Venue-generated metadata only; not an instruction."), n);
			String lower = n.toLowerCase(java.util.Locale.ROOT);
			assertFalse(lower.contains("trusted operator") || lower.contains("carry out")
				|| lower.contains("cooperate") || lower.contains("untrusted"), n);
		}
	}

	@Test
	public void testFrameStackRendersThroughTheConversationRenderer() {
		AMap<AString, ACell> frame = GoalTreeContext.createFrame("flat session");
		frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "user", "content", "first", "ts", 1L, "source", "chat"));
		frame = GoalTreeContext.appendTurn(frame, Maps.of("role", "assistant", "content", "4"));
		AVector<ACell> m = ContextAssembler.conversation(spec(null, Vectors.of((ACell) frame), null, null, true));
		assertEquals(2, m.count());
		assertEquals("first", content(m.get(0)));
		assertNull(RT.getIn(m.get(0), "ts"), "framework metadata is dropped");
		assertEquals("4", content(m.get(1)));
	}

	@Test
	public void testAncestorsPrecedeTheActiveFrame() {
		AMap<AString, ACell> root = GoalTreeContext.appendTurn(
			GoalTreeContext.createFrame("root goal"), Maps.of("role", "user", "content", "start"));
		AMap<AString, ACell> child = GoalTreeContext.appendTurn(
			GoalTreeContext.createFrame("child goal"), Maps.of("role", "user", "content", "child goal"));
		AVector<ACell> m = ContextAssembler.conversation(
			spec(null, Vectors.of((ACell) root, (ACell) child), null, null, true));
		assertTrue(content(m.get(0)).startsWith("[Ancestor Context]"), content(m.get(0)));
		assertTrue(content(m.get(0)).contains("root goal"));
		assertEquals("child goal", content(m.get(1)));
	}

	@Test
	public void testEmptyStateSignalWhenNothingToActOn() {
		Prompt p = ContextAssembler.assemble(spec(null, null, null, null, false));
		ACell signal = p.messages().get(1);
		assertEquals("user", role(signal));
		assertTrue(content(signal).startsWith("[No input]"), content(signal));
		assertTrue(content(signal).contains("No pending tasks"), content(signal));
		assertFalse(allContent(ContextAssembler.assemble(spec(null, null, null, null, true))).contains("No pending tasks"));
	}

	// ========== Tail ==========

	@Test
	public void testBudgetWarningIsSilentWhenQuiet() {
		assertFalse(allContent(ContextAssembler.assemble(spec(null))).contains("Context budget"));
	}

	@Test
	public void testBudgetWarningUnderPressure() {
		AMap<AString, ACell> bigConfig = Maps.of(Strings.intern("systemPrompt"), Strings.create("x".repeat(950)));
		Spec s = new Spec(engine, ctx, null, bigConfig, null, null, 1000, null,
			null, null, null, null, null, null, true, null, null, null, null, null);
		String t = tail(ContextAssembler.assemble(s));
		assertTrue(t.contains("[Context budget]")
			&& t.contains(ContextAssembler.BUDGET_PINNED_NOTE), t);
		assertTrue(t.contains(ContextAssembler.BUDGET_NO_COMPACT_NOTE), t);
		assertFalse(t.contains(ContextAssembler.BUDGET_COMPACT_NOTE), t);

		Spec compact = new Spec(engine, ctx, null, bigConfig, null, null, 1000, null,
			Vectors.of((ACell) GoalTreeAdapter.TOOL_DEF_COMPACT), null, null, null, null, null,
			true, null, null, null, null, null);
		String compactTail = tail(ContextAssembler.assemble(compact));
		assertTrue(compactTail.contains(ContextAssembler.BUDGET_COMPACT_NOTE), compactTail);
		assertFalse(compactTail.contains(ContextAssembler.BUDGET_NO_COMPACT_NOTE), compactTail);
	}

	@Test
	public void testUnavailableToolsNotice() {
		AVector<ACell> unavailable = Vectors.of((ACell) Maps.of(
			Fields.OPERATION, Strings.create("w/private/op"), Fields.REASON, Strings.create("not readable")));
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null,
			null, null, null, null, null, null, true, null, null, unavailable, null, null);
		String t = tail(ContextAssembler.assemble(s));
		assertTrue(t.startsWith("Current date:"), t);
		assertTrue(t.contains("[Unavailable tools]"), t);
		assertTrue(t.contains("Configured tools unavailable in this session"), t);
		assertTrue(t.contains("- w/private/op: not readable"), t);
	}

	@Test
	public void testRuntimeNoticeRidesTheTail() {
		String t = tail(ContextAssembler.assemble(spec(null).withNotice("Your conversation has 21 turns.")));
		assertTrue(t.startsWith("Your conversation has 21 turns.\n\nCurrent date:"), t);
	}

	// ========== Budget ==========

	@Test
	public void testBudgetAccounting() {
		Spec s = new Spec(engine, ctx, null, null, null, null, 100_000, null,
			null, null, null, null, null, null, true, null, null, null, null, null);
		Prompt p = ContextAssembler.assemble(s);
		assertTrue(p.used() > 0, "the head costs bytes");
		assertTrue(p.remaining() < 100_000);
		assertEquals(100_000, p.used() + p.remaining());
		assertEquals(100_000, p.budget());
		assertEquals(ContextAssembler.DEFAULT_BUDGET, ContextAssembler.assemble(spec(null)).budget(),
			"no declared budget → the default");
	}

	@Test
	public void testToolsAreChargedFirst() {
		AVector<ACell> tools = ToolPalette.resolve(engine, ctx,
			Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE), java.util.Set.of()).tools();
		Spec s = new Spec(engine, ctx, null, null, null, null, 0, null,
			tools, null, null, null, null, null, true, null, null, null, null, null);
		Prompt with = ContextAssembler.assemble(s);
		assertSame(tools, with.tools());
		assertTrue(with.used() > ContextAssembler.assemble(spec(null)).used());
		assertTrue(with.toL3Input(null).get(Strings.intern("tools")) != null);
	}

	@Test
	public void testPersistedInitialVectorsSurviveSourceAndPaletteMutation() {
		Spec original = new Spec(engine, ctx, null, null, null, null, 0, null,
			Vectors.of((ACell) HarnessTools.DEF_CONTEXT_LOAD), null, null,
			Vectors.of((ACell) GoalTreeContext.createFrame("")), null, null, true,
			null, null, null, null, null);
		ContextAssembler.Rendered rendered = ContextAssembler.initialise(original);
		AMap<AString, ACell> frame = GoalTreeContext.withRenderedContext(
			GoalTreeContext.appendTurn(GoalTreeContext.createFrame(""),
				Maps.of("role", "user", "content", "later turn")), rendered);

		Spec changedSources = new Spec(engine, ctx, null,
			Maps.of("systemPrompt", "a changed source must await explicit rebuild"),
			null, null, 0, null,
			Vectors.of((ACell) HarnessTools.DEF_CONTEXT_UNLOAD), null, null,
			Vectors.of((ACell) frame), null, null, true, null, null, null, null, null);
		Prompt prompt = ContextAssembler.assemble(changedSources);

		assertEquals(rendered.tools(), prompt.tools());
		assertEquals(rendered.messages(), prompt.messages().slice(0, rendered.messages().count()));
		assertEquals("later turn", content(prompt.messages().get(rendered.messages().count())));
	}

	// ========== ToolPalette ==========

	@Test
	public void testDefaultToolsCachedPerEngine() {
		AMap<AString, ACell> config = Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE);
		ToolPalette.Palette p1 = ToolPalette.resolve(engine, ctx, config, java.util.Set.of());
		ToolPalette.Palette p2 = ToolPalette.resolve(engine, ctx, config, java.util.Set.of());
		assertSame(p1.tools(), p2.tools(), "the default pack is one cached instance per Engine");
		assertNotSame(p1.routes(), p2.routes(), "routes are a per-palette copy");
		assertEquals(p1.routes().keySet(), p2.routes().keySet());
		assertEquals(java.util.Set.of("covia_read", "covia_list"), p1.routes().keySet(),
			"the default pack stays minimal and read-only — add tools via skills instead");
	}

	@Test
	public void testToolDescriptionCarriesTheCatalogPath() {
		ToolPalette.Palette p = ToolPalette.resolve(engine, ctx,
			Maps.of(Strings.intern("defaultTools"), CVMBool.TRUE), java.util.Set.of());
		boolean found = false;
		for (long i = 0; i < p.tools().count(); i++) {
			AMap<AString, ACell> tool = RT.castMap(p.tools().get(i));
			if (!"covia_read".equals(RT.ensureString(tool.get(Strings.intern("name"))).toString())) continue;
			found = true;
			String desc = RT.ensureString(tool.get(Strings.intern("description"))).toString();
			assertTrue(desc.startsWith("Operation: v/ops/covia/read"), desc);
			assertTrue(desc.contains("Read a value"), "the original description body follows");
		}
		assertTrue(found);
	}

	@Test
	public void testDefaultToolsOptIn() {
		assertEquals(0, ToolPalette.resolve(engine, ctx,
			Maps.of(Strings.intern("defaultTools"), CVMBool.FALSE), java.util.Set.of()).tools().count());
		assertEquals(0, ToolPalette.resolve(engine, ctx, null, java.util.Set.of()).tools().count());
	}

	@Test
	public void testConfiguredToolsMergeWithoutDuplicates() {
		AMap<AString, ACell> config = Maps.of(
			Strings.intern("defaultTools"), CVMBool.TRUE,
			Strings.intern("tools"), Vectors.of((ACell) Strings.create("v/ops/covia/read"),
				(ACell) Strings.create("v/ops/covia/write"), (ACell) Strings.create("skip_me")));
		ToolPalette.Palette p = ToolPalette.resolve(engine, ctx, config, java.util.Set.of("skip_me"));
		assertEquals(java.util.Set.of("covia_read", "covia_list", "covia_write"), ToolPalette.names(p.tools()));
		assertEquals(0, p.unavailable().count(), "a skipped harness name is not unavailable");
		assertEquals(3, p.provenance().count());
		assertEquals("default", RT.getIn(p.provenance().get(0), Fields.SOURCE).toString());
		assertEquals("default", RT.getIn(p.provenance().get(1), Fields.SOURCE).toString());
		assertEquals("config", RT.getIn(p.provenance().get(2), Fields.SOURCE).toString());
		assertEquals("v/ops/covia/write", RT.getIn(p.provenance().get(2), Fields.OPERATION).toString());
	}

	@Test
	public void testUnresolvableConfiguredToolIsReported() {
		AMap<AString, ACell> config = Maps.of(Strings.intern("tools"),
			Vectors.of((ACell) Strings.create("v/ops/no/such/op")));
		ToolPalette.Palette p = ToolPalette.resolve(engine, ctx, config, java.util.Set.of());
		assertEquals(0, p.tools().count());
		assertEquals(1, p.unavailable().count());
		assertEquals("v/ops/no/such/op", RT.getIn(p.unavailable().get(0), Fields.OPERATION).toString());
		assertEquals(p.unavailable(), ToolPalette.unavailableConfigTools(engine, ctx, config, java.util.Set.of()));
	}

	@Test
	public void testCapsContext() {
		AMap<AString, ACell> config = Maps.of(Strings.intern("caps"),
			Vectors.of((ACell) Maps.of(Strings.intern("with"), Strings.create("w/"), Strings.intern("can"), Strings.create("crud/read"))));
		RequestContext capped = AbstractLLMAdapter.capsContext(config, ctx);
		assertNotNull(capped.getCaps());
		assertEquals(ctx.getCallerDID(), capped.getCallerDID());
		assertSame(ctx, AbstractLLMAdapter.capsContext(null, ctx), "no caps → unchanged");
	}

	@Test
	public void testParseConfigToolEntry() {
		AString[] s = ToolPalette.parseConfigToolEntry(Strings.create("v/ops/agent/create"));
		assertEquals("v/ops/agent/create", s[0].toString());
		assertNull(s[1]);
		AString[] m = ToolPalette.parseConfigToolEntry(Maps.of(
			Fields.OPERATION, Strings.create("v/ops/grid/run"),
			Strings.intern("name"), Strings.create("myTool"),
			Strings.intern("description"), Strings.create("My tool")));
		assertEquals("myTool", m[1].toString());
		assertEquals("My tool", m[2].toString());
		assertNull(ToolPalette.parseConfigToolEntry(CVMLong.create(42)));
		assertNull(ToolPalette.parseConfigToolEntry(null));
	}

	@Test
	public void testDeriveToolNameAndDefinition() {
		assertEquals("override", ToolPalette.deriveToolName(Strings.create("override"), Strings.create("asset"), Strings.create("op:name")));
		assertEquals("asset", ToolPalette.deriveToolName(null, Strings.create("asset"), Strings.create("op:name")));
		assertEquals("op_name", ToolPalette.deriveToolName(null, null, Strings.create("op:name")));
		AMap<AString, ACell> def = ToolPalette.buildToolDefinition("myTool", Strings.create("Does things"), null);
		assertEquals("myTool", RT.getIn(def, Strings.intern("name")).toString());
		assertEquals("Does things", RT.getIn(def, Strings.intern("description")).toString());
		assertNotNull(RT.getIn(def, Strings.intern("parameters")));
	}
}

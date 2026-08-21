package covia.adapter.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import convex.core.lang.RT;
import covia.adapter.agent.Labels.Kind;

/** One renderer, three dialects: the label table of AGENT_CONTEXT.md §1.1. */
public class LabelsTest {

	@Test
	public void testBracketIsTheDefaultShape() {
		assertEquals("[Skill: alpha — w/skills/alpha]\n## Alpha\nDo it.",
			Labels.render(Labels.BRACKET, Kind.SKILL, "## Alpha\nDo it.", "alpha", "w/skills/alpha"));
		assertEquals("[Context: notes]\nbody",
			Labels.render(Labels.BRACKET, Kind.CONTEXT, "body", "notes"));
		assertEquals("[Skills]", Labels.render(Labels.BRACKET, Kind.SKILLS, null));
		// Inline kinds keep a one-line body on the label's line.
		assertEquals("[Compacted: 5 turns] did things",
			Labels.render(Labels.BRACKET, Kind.COMPACTED, "did things", "5"));
		assertEquals("[Context budget] 72% used",
			Labels.render(Labels.BRACKET, Kind.BUDGET, "72% used"));
		assertEquals("[Context: notes — unavailable: gone]",
			Labels.renderUnavailable(Labels.BRACKET, Kind.CONTEXT, "gone", "notes"));
		assertEquals("[system: Current date: 2026-01-01.]",
			Labels.wrapSystem(Labels.BRACKET, "Current date: 2026-01-01."));
	}

	@Test
	public void testXmlClosesWhatItOpens() {
		assertEquals("<skill name=\"alpha\" path=\"w/skills/alpha\">body</skill>",
			Labels.render(Labels.XML, Kind.SKILL, "body", "alpha", "w/skills/alpha"));
		assertEquals("<skills/>", Labels.render(Labels.XML, Kind.SKILLS, null));
		assertEquals("<compacted turns=\"5\">did things</compacted>",
			Labels.render(Labels.XML, Kind.COMPACTED, "did things", "5"));
		assertEquals("<context label=\"notes\" unavailable=\"gone\"/>",
			Labels.renderUnavailable(Labels.XML, Kind.CONTEXT, "gone", "notes"));
		assertEquals("<system>x</system>", Labels.wrapSystem(Labels.XML, "x"));
		// Attribute values are escaped; bodies are verbatim.
		assertEquals("<context label=\"a &quot;b&quot; &lt;c>\">1 < 2</context>",
			Labels.render(Labels.XML, Kind.CONTEXT, "1 < 2", "a \"b\" <c>"));
	}

	@Test
	public void testHeaderIsAHeading() {
		assertEquals("## Skill: alpha — w/skills/alpha\nbody",
			Labels.render(Labels.HEADER, Kind.SKILL, "body", "alpha", "w/skills/alpha"));
		assertEquals("## Skills", Labels.render(Labels.HEADER, Kind.SKILLS, null));
		assertEquals("## Context: notes — unavailable: gone",
			Labels.renderUnavailable(Labels.HEADER, Kind.CONTEXT, "gone", "notes"));
		assertEquals("## System\nx", Labels.wrapSystem(Labels.HEADER, "x"));
	}

	@Test
	public void testMessageCarriesRoleAndRenderedContent() {
		var msg = Labels.message(AbstractLLMAdapter.ROLE_SYSTEM, Labels.BRACKET, Kind.PENDING, "- Job 1");
		assertEquals("system", RT.getIn(msg, "role").toString());
		assertEquals("[Pending job results]\n- Job 1", RT.getIn(msg, "content").toString());
	}
}

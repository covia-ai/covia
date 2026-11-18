package covia.adapter.agent;

import java.util.List;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;

/**
 * The one renderer for context element labels (AGENT_CONTEXT.md §1.1).
 *
 * <p>Every element the assembler renders is {@code {kind, key?, body}}. This
 * class turns that into text in one of three dialects chosen by the model's
 * {@code labels} option: {@code bracket} (the default, {@code [Label …]}),
 * {@code xml} (elements with explicit closing tags) or {@code header}
 * (markdown headings). Labels are the interface between the prompt and
 * everything that reads it back — the model, {@code agent:context} and the
 * tests — so no section concatenates its own header.</p>
 */
public final class Labels {

	private Labels() {}

	public static final AString BRACKET = AbstractLLMAdapter.LABELS_BRACKET;
	public static final AString XML     = AbstractLLMAdapter.LABELS_XML;
	public static final AString HEADER  = AbstractLLMAdapter.LABELS_HEADER;

	private static final AString K_ROLE    = Strings.intern("role");
	private static final AString K_CONTENT = Strings.intern("content");

	/**
	 * The element kinds. {@code pattern} is the bracket/header label with one
	 * {@code %s} per attribute; {@code tag} and {@code attributes} are the xml
	 * form; {@code inline} kinds carry a one-line body on the label's own line
	 * in the bracket dialect.
	 */
	public enum Kind {
		SKILLS("Skills", "skills", false),
		SKILL("Skill: %s — %s", "skill", false, "name", "path"),
		PINNED_SKILL("Pinned skill: %s — %s", "pinned-skill", false, "name", "source"),
		LOADED_SKILL("Loaded skill: %s — unload key: %s", "loaded-skill", false, "name", "unload-key"),
		PINNED_CONTEXT("Pinned context: %s", "pinned-context", false, "label"),
		COMPACTED("Compacted: %s turns", "compacted", true, "turns"),
		COMPACTED_TURNS("Compacted: turns %s–%s", "compacted", true, "from", "to"),
		ANCESTORS("Ancestor Context", "ancestors", false),
		TOOL_FAILURE("Tool failure: %s", "tool-failure", false, "name"),
		PENDING("Pending job results", "pending-results", false),
		TASKS("Tasks assigned to you", "tasks", false),
		NO_INPUT("No input", "no-input", false),
		BUDGET("Context budget", "context-budget", true),
		UNAVAILABLE_TOOLS("Unavailable tools", "unavailable-tools", false),
		/** Who the following turn came from ({@link TurnProvenance}); attributes vary by turn. */
		FROM("From: %s", "from", true),
		/** Who the following turn came from, for a turn whose route names where its request started: one sentence ({@link TurnProvenance}). */
		FROM_VENUE("From (venue-verified): %s", "from", true);

		final String pattern;
		final String tag;
		final boolean inline;
		final String[] attributes;

		Kind(String pattern, String tag, boolean inline, String... attributes) {
			this.pattern = pattern;
			this.tag = tag;
			this.inline = inline;
			this.attributes = attributes;
		}
	}

	/** A labelled element: the label, then the body (null for a label-only element). */
	public static String render(AString dialect, Kind kind, String body, String... values) {
		if (XML.equals(dialect)) {
			StringBuilder sb = openTag(kind, values);
			if (body == null) return sb.append("/>").toString();
			return sb.append('>').append(body).append("</").append(kind.tag).append('>').toString();
		}
		String label = String.format(kind.pattern, (Object[]) values);
		if (HEADER.equals(dialect)) {
			return (body == null) ? "## " + label : "## " + label + "\n" + body;
		}
		if (body == null) return "[" + label + "]";
		return "[" + label + "]" + (kind.inline ? " " : "\n") + body;
	}

	/**
	 * A label-only element whose attributes vary by instance: the bracket and header
	 * label is the kind's pattern filled with {@code summary}; xml carries each
	 * {@code name, value} pair given, in order, escaped.
	 */
	public static String renderAttributed(AString dialect, Kind kind, String summary, String... namesAndValues) {
		return renderAttributed(dialect, kind, summary, List.of(), namesAndValues);
	}

	/**
	 * As {@link #renderAttributed(AString, Kind, String, String...)}, with child
	 * elements in xml: each child is its tag followed by {@code name, value}
	 * pairs, escaped, and the element closes after them. With no children it is
	 * the label-only element. The bracket and header label is {@code summary}
	 * alone, which must already say what the children do.
	 */
	public static String renderAttributed(AString dialect, Kind kind, String summary, List<String[]> children,
			String... namesAndValues) {
		if (XML.equals(dialect)) {
			StringBuilder sb = new StringBuilder("<").append(kind.tag);
			appendAttributes(sb, namesAndValues, 0);
			if (children.isEmpty()) return sb.append("/>").toString();
			sb.append('>');
			for (String[] child : children) {
				sb.append('<').append(child[0]);
				appendAttributes(sb, child, 1);
				sb.append("/>");
			}
			return sb.append("</").append(kind.tag).append('>').toString();
		}
		String label = String.format(kind.pattern, summary);
		return HEADER.equals(dialect) ? "## " + label : "[" + label + "]";
	}

	/**
	 * A label-only element that is one venue-written sentence: the bracket and
	 * header label is {@code kind}'s pattern filled with it; xml carries it as
	 * the element's escaped text, marked as the venue's. Brackets and line
	 * breaks never reach the label, so the sentence cannot close it.
	 */
	public static String renderSentence(AString dialect, Kind kind, String sentence) {
		String line = sentence.replace('[', '(').replace(']', ')').replace('\n', ' ').replace('\r', ' ');
		if (XML.equals(dialect)) {
			return "<" + kind.tag + " verified=\"venue\">" + attr(line).replace(">", "&gt;") + "</" + kind.tag + ">";
		}
		String label = String.format(kind.pattern, line);
		return HEADER.equals(dialect) ? "## " + label : "[" + label + "]";
	}

	/** Each {@code name, value} pair of {@code pairs} from {@code from} on, as an escaped xml attribute. */
	private static void appendAttributes(StringBuilder sb, String[] pairs, int from) {
		for (int i = from; i + 1 < pairs.length; i += 2) {
			sb.append(' ').append(pairs[i]).append("=\"").append(attr(pairs[i + 1])).append('"');
		}
	}

	/** An element whose source failed: the label carries the reason, there is no body. */
	public static String renderUnavailable(AString dialect, Kind kind, String reason, String... values) {
		String r = (reason != null && !reason.isEmpty()) ? reason : "resolution failed";
		if (XML.equals(dialect)) {
			return openTag(kind, values).append(" unavailable=\"").append(attr(r)).append("\"/>").toString();
		}
		String label = String.format(kind.pattern, (Object[]) values) + " — unavailable: " + r;
		return HEADER.equals(dialect) ? "## " + label : "[" + label + "]";
	}

	/** The wrapper a provider edge applies to a system message that follows the
	 *  conversation, where the provider has no system role in its message list. */
	public static String wrapSystem(AString dialect, String content) {
		if (isRenderedElement(dialect, content)) return content;
		if (XML.equals(dialect)) return "<system>" + content + "</system>";
		if (HEADER.equals(dialect)) return "## System\n" + content;
		return "[system: " + content + "]";
	}

	/** A canonical labelled element already carries its venue-authored meaning
	 * after a single-system provider moves it into a user-role message. */
	private static boolean isRenderedElement(AString dialect, String content) {
		if (content == null) return false;
		for (Kind kind : Kind.values()) {
			if (XML.equals(dialect) && content.startsWith("<" + kind.tag)) return true;
			String prefix = kind.pattern.split("%s", 2)[0];
			if (HEADER.equals(dialect) && content.startsWith("## " + prefix)) return true;
			if (!XML.equals(dialect) && !HEADER.equals(dialect)
					&& content.startsWith("[" + prefix)) return true;
		}
		return false;
	}

	/** A {@code {role, content}} message carrying one rendered element. */
	public static AMap<AString, ACell> message(AString role, AString dialect, Kind kind,
			String body, String... values) {
		return Maps.of(K_ROLE, role, K_CONTENT, Strings.create(render(dialect, kind, body, values)));
	}

	private static StringBuilder openTag(Kind kind, String... values) {
		StringBuilder sb = new StringBuilder("<").append(kind.tag);
		for (int i = 0; i < kind.attributes.length && i < values.length; i++) {
			sb.append(' ').append(kind.attributes[i]).append("=\"").append(attr(values[i])).append('"');
		}
		return sb;
	}

	private static String attr(String value) {
		return (value == null) ? "" : value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
	}
}

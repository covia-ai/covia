package covia.adapter.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.adapter.messaging.ConversationRouter;
import covia.api.Fields;

/**
 * Markdown to Telegram HTML ({@link TelegramMarkdown}, {@code telegram:format}).
 * The contract is that Telegram accepts every output, so every fixture goes through
 * one structural check, {@link #assertTelegramHtml}, before anything is said about
 * what it means.
 */
public class TelegramMarkdownTest {

	private static final Set<String> TELEGRAM_TAGS = Set.of("b", "i", "u", "s", "a", "code", "pre", "blockquote", "span");
	private static final Pattern TAG = Pattern.compile("<(/?)([a-z]+)((?:\\s+[a-z-]+=\"[^\"<>]*\")*)>");
	private static final Pattern ENTITY = Pattern.compile("&(amp|lt|gt|quot);");

	/** Shapes a model's reply takes: Mina's on Telegram (#612 in GetMine) among them. */
	private static final List<String> FIXTURES = List.of(
		"## Your summary\n\n**What's in your Vault:**\n- 23 records\n- **Medications:** 4 active\n  - Metformin 500 mg\n  - Ramipril\n\n1. First\n2. Second",
		"| Date | Value |\n|---|---|\n| 2026-10-01 | 5.4 mmol/L |\n| 2026-10-08 | 6.1 |",
		"See [the NHS page](https://www.nhs.uk/conditions/) and `a*b*c` here.",
		"Ratio < 3 & > 1 is fine, AT&T <b>not bold</b> **unclosed",
		"```python\nif a < b:\n    print('x & y')\n```",
		"> outer\n> > inner\n> more",
		"[click](javascript:alert(1)) and [rel](/local/path)",
		"![chart](https://example.com/c.png)",
		"# One\n### Three\n\n~~old~~ new\n\n---\n\n<div>raw <i>html</i></div>",
		"- item with **bold\n  across** lines\n\n  second paragraph\n- `code` & <x>",
		"5. five\n6. six",
		"line one\nline two");

	@Test
	public void everyOutputIsHtmlTelegramAccepts() {
		for (String fixture : FIXTURES) assertTelegramHtml(TelegramMarkdown.toHtml(fixture));
	}

	@Test
	public void formattingKeepsItsMeaning() {
		String summary = TelegramMarkdown.toHtml(FIXTURES.get(0));
		assertTrue(summary.contains("<b>Your summary</b>"), summary);
		assertTrue(summary.contains("<b>What's in your Vault:</b>"), summary);
		assertTrue(summary.contains("• <b>Medications:</b> 4 active"), summary);
		assertTrue(summary.contains("\n   ◦ Metformin 500 mg"), "a nested item is indented under its parent: " + summary);
		assertTrue(summary.contains("1. First\n2. Second"), summary);
		assertFalse(summary.contains("**"), summary);

		String link = TelegramMarkdown.toHtml(FIXTURES.get(2));
		assertTrue(link.contains("<a href=\"https://www.nhs.uk/conditions/\">the NHS page</a>"), link);
		assertTrue(link.contains("<code>a*b*c</code>"), link);

		String struck = TelegramMarkdown.toHtml(FIXTURES.get(8));
		assertTrue(struck.contains("<s>old</s>"), struck);
		assertTrue(struck.contains("<b>One</b>") && struck.contains("<b>Three</b>"), struck);

		assertEquals("5. five\n6. six", TelegramMarkdown.toHtml(FIXTURES.get(10)), "a list keeps its own numbering");
	}

	@Test
	public void sourceHtmlAndStrayMarkupAreShownNotObeyed() {
		String escaped = TelegramMarkdown.toHtml(FIXTURES.get(3));
		assertTrue(escaped.contains("&lt; 3 &amp; &gt; 1"), escaped);
		assertTrue(escaped.contains("AT&amp;T"), escaped);
		assertTrue(escaped.contains("&lt;b&gt;not bold&lt;/b&gt;"), "raw HTML in the source is text: " + escaped);
		assertTrue(escaped.contains("**unclosed"), "an unclosed marker stays as written: " + escaped);

		String block = TelegramMarkdown.toHtml(FIXTURES.get(8));
		assertTrue(block.contains("&lt;div&gt;raw &lt;i&gt;html&lt;/i&gt;&lt;/div&gt;"), block);
	}

	@Test
	public void codeBlocksTablesAndQuotesBecomeTheirTelegramBlocks() {
		String code = TelegramMarkdown.toHtml(FIXTURES.get(4));
		assertTrue(code.startsWith("<pre><code class=\"language-python\">"), code);
		assertTrue(code.contains("if a &lt; b:\n    print('x &amp; y')"), "code keeps its lines and indentation: " + code);

		String table = TelegramMarkdown.toHtml(FIXTURES.get(1));
		assertTrue(table.startsWith("<pre>") && table.endsWith("</pre>"), table);
		String[] rows = table.substring(5, table.length() - 6).split("\n");
		assertEquals(4, rows.length, "head, rule and two rows: " + table);
		int column = rows[0].indexOf("Value");
		assertEquals(column, rows[2].indexOf("5.4"), "columns line up: " + table);
		assertEquals(column, rows[3].indexOf("6.1"), table);

		String quote = TelegramMarkdown.toHtml(FIXTURES.get(5));
		assertEquals(1, count(quote, "<blockquote>"), "a nested quote is flattened into the outer one: " + quote);
		assertTrue(quote.contains("outer") && quote.contains("inner") && quote.contains("more"), quote);
	}

	@Test
	public void onlyLinksTelegramOpensAreLinks() {
		String unsafe = TelegramMarkdown.toHtml(FIXTURES.get(6));
		assertFalse(unsafe.contains("href"), unsafe);
		assertTrue(unsafe.contains("click") && unsafe.contains("rel"), "the text stays: " + unsafe);

		String image = TelegramMarkdown.toHtml(FIXTURES.get(7));
		assertEquals("<a href=\"https://example.com/c.png\">chart</a>", image);
	}

	@Test
	public void linesStayWhereTheModelPutThem() {
		assertEquals("line one\nline two", TelegramMarkdown.toHtml(FIXTURES.get(11)),
			"a soft line break is a newline in a chat, not a space");
		String item = TelegramMarkdown.toHtml(FIXTURES.get(9));
		assertTrue(item.contains("• item with <b>bold\n   across</b> lines\n   second paragraph"),
			"an item's later lines sit under its text: " + item);
		assertEquals("", TelegramMarkdown.toHtml(""));
		assertEquals("", TelegramMarkdown.toHtml(null));
	}

	@Test
	public void theOperationTakesMarkdownTextOrAReplyHookResponse() {
		ACell direct = TelegramAdapter.handleFormat(Maps.of("markdown", "**hi**"));
		assertEquals("<b>hi</b>", RT.getIn(direct, Fields.TEXT).toString());
		assertEquals(TelegramMarkdown.PARSE_MODE, RT.getIn(direct, TelegramAdapter.K_PARSE_MODE_PARAM).toString());
		assertEquals("<b>hi</b>", RT.getIn(TelegramAdapter.handleFormat(Strings.create("**hi**")), Fields.TEXT).toString());
		assertEquals("<b>hi</b>", RT.getIn(TelegramAdapter.handleFormat(Maps.of("text", "**hi**")), Fields.TEXT).toString());
		// The reply hook's own input: the response read as the adapter reads any reply.
		ACell hooked = TelegramAdapter.handleFormat(Maps.of(
			"response", Maps.of("text", "**hi**"), "via", Maps.of("channel", "telegram")));
		assertEquals("<b>hi</b>", RT.getIn(hooked, Fields.TEXT).toString());
		ACell empty = TelegramAdapter.handleFormat(Maps.of("response", Strings.create("  "), "via", Maps.empty()));
		assertEquals(TelegramMarkdown.escape(ConversationRouter.NO_RESPONSE),
			RT.getIn(empty, Fields.TEXT).toString(), "an empty reply reads as the adapter's own no-response text");
		// A hook's parse_mode wins over the bot's; one Telegram does not know does not.
		assertEquals(TelegramMarkdown.PARSE_MODE, BotRunner.formattedParseMode(direct, null));
		assertEquals(TelegramMarkdown.PARSE_MODE, BotRunner.formattedParseMode(direct, "MarkdownV2"));
		assertEquals("MarkdownV2", BotRunner.formattedParseMode(Maps.of("text", "x"), "MarkdownV2"));
		assertEquals("MarkdownV2", BotRunner.formattedParseMode(Strings.create("x"), "MarkdownV2"));
		assertEquals(null, BotRunner.formattedParseMode(Maps.of("text", "x", "parse_mode", "Shouty"), null));
		IllegalArgumentException nothing = assertThrows(IllegalArgumentException.class,
			() -> TelegramAdapter.handleFormat(Maps.of("via", Maps.empty())));
		assertEquals(TelegramAdapter.NOTHING_TO_FORMAT, nothing.getMessage());
	}

	/**
	 * What Telegram's HTML parser accepts: only its tags, each closed in order, no tag
	 * inside {@code code} or {@code pre} but {@code pre}'s own {@code code}, no quote in
	 * a quote, and every {@code <}, {@code >} and {@code &} outside a tag an entity.
	 */
	static void assertTelegramHtml(String html) {
		Deque<String> open = new ArrayDeque<>();
		int i = 0;
		while (i < html.length()) {
			char c = html.charAt(i);
			if (c == '<') {
				Matcher tag = TAG.matcher(html).region(i, html.length());
				if (!tag.lookingAt()) fail("an unescaped < at " + i + ": " + html);
				String name = tag.group(2);
				assertTrue(TELEGRAM_TAGS.contains(name), "<" + name + "> is not Telegram's: " + html);
				if (tag.group(1).isEmpty()) {
					String inside = open.peek();
					if ("pre".equals(inside)) assertEquals("code", name, "pre holds only its code: " + html);
					else assertFalse("code".equals(inside), "nothing inside code: " + html);
					if (name.equals("blockquote")) assertFalse(open.contains("blockquote"), "a quote in a quote: " + html);
					open.push(name);
				} else {
					assertFalse(open.isEmpty(), "</" + name + "> closes nothing: " + html);
					assertEquals(open.pop(), name, "tags close in order: " + html);
				}
				i = tag.end();
			} else if (c == '>') {
				fail("an unescaped > at " + i + ": " + html);
			} else if (c == '&') {
				Matcher entity = ENTITY.matcher(html).region(i, html.length());
				if (!entity.lookingAt()) fail("an unescaped & at " + i + ": " + html);
				i = entity.end();
			} else {
				i++;
			}
		}
		assertTrue(open.isEmpty(), "unclosed " + open + ": " + html);
	}

	private static int count(String text, String of) {
		int n = 0;
		for (int at = text.indexOf(of); at >= 0; at = text.indexOf(of, at + 1)) n++;
		return n;
	}
}

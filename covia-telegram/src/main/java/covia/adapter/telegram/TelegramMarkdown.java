package covia.adapter.telegram;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * Markdown, as a model writes it, to the HTML Telegram accepts.
 *
 * <p>Telegram's HTML is a small dialect: {@code b}, {@code i}, {@code u},
 * {@code s}, {@code a href}, {@code code}, {@code pre}, {@code blockquote} and
 * {@code span class="tg-spoiler"}, with newlines taken literally. Its nesting
 * rules are strict (Bot API, "Formatting options"): bold, italic, underline,
 * strikethrough and spoiler may contain and sit inside anything but code and
 * pre; a quote holds no quote; and all other entities (code, pre, links,
 * quotes) contain none of each other.
 * A message that breaks them is refused, and the adapter then resends it as
 * plain text ({@link #htmlToPlain}), so a general Markdown renderer is the
 * wrong tool. Every string {@link #toHtml} returns keeps to that dialect:
 *
 * <ul>
 *   <li>bold, italic, strikethrough ({@code ~~}, two tildes only: a single
 *       {@code ~} is "about" in "~30 minutes"), inline code and links keep
 *       their meaning; a link whose scheme Telegram does not open keeps its
 *       text only;</li>
 *   <li>inline code inside bold, italic or strikethrough is set apart from it
 *       (the formatting closes before the code and reopens after); inside a
 *       link it is the link's text, and a link inside a link is text;</li>
 *   <li>a heading becomes a bold line, a list item a bullet or numbered line
 *       (nested items indented), a code block a {@code pre}, a table a
 *       monospaced {@code pre} grid, a rule a line of dashes; a code block or
 *       table in a list item starts its own line, unindented, so its own
 *       lines keep their spacing;</li>
 *   <li>a block quote becomes a {@code blockquote}, nested quotes flattened
 *       into it; a code block or table inside one closes the quote and stands
 *       on its own, and inside the quote inline code is its text, a link its
 *       text with its address in brackets, and code nested deeper (in a quoted
 *       list) plain lines;</li>
 *   <li>raw HTML in the source is shown as text, never passed through, and
 *       every {@code &}, {@code <} and {@code >} outside a tag is escaped;</li>
 *   <li>a block with no visible text (a lone {@code #}, an empty fence) is
 *       dropped.</li>
 * </ul>
 *
 * <p>A soft line break stays a newline. CommonMark renders it as a space, but a
 * model writing for a chat means the line it wrote, and Telegram shows newlines
 * as they are.
 *
 * <p>The adapter splits a long reply at a line break where it can. An inline
 * tag crosses a line only where the source's own emphasis does, so it nearly
 * always stays whole; a part whose tags the cut separates, most often a long
 * {@code pre} or {@code blockquote}, is refused and resent as plain text.
 */
public final class TelegramMarkdown {

	/** The {@code parse_mode} under which {@link #toHtml}'s output is sent. */
	public static final String PARSE_MODE = "HTML";

	private static final List<Extension> EXTENSIONS = List.of(
		TablesExtension.create(),
		StrikethroughExtension.builder().requireTwoTildes(true).build());
	private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

	/** Link schemes Telegram opens; anything else keeps its text and drops the link. */
	private static final Pattern OPENABLE = Pattern.compile("(?i)^(https?|tg|mailto):.+");
	/** A fence's language, as Telegram's {@code language-} class allows. */
	private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_+#.-]{1,32}");
	/** Any tag of {@link #toHtml}'s output: its attributes never hold a raw {@code >}. */
	private static final Pattern TAG = Pattern.compile("<[^>]*>");
	/** A formatting pair with nothing between, left where code was set apart from it. */
	private static final Pattern EMPTY_PAIR = Pattern.compile("<(b|i|s)></\\1>");
	private static final String[] BULLETS = { "•", "◦", "▪" };
	private static final String INDENT = "   ";
	private static final String RULE = "———";

	private TelegramMarkdown() {
	}

	/** Markdown to Telegram HTML; null or empty in, empty out. */
	public static String toHtml(String markdown) {
		if (markdown == null || markdown.isEmpty()) return "";
		Node document = PARSER.parse(markdown);
		String html = new Renderer().blocks(document, 0, "\n\n").strip();
		for (String previous = null; !html.equals(previous); ) {
			previous = html;
			html = EMPTY_PAIR.matcher(html).replaceAll("");
		}
		return html;
	}

	/**
	 * {@link #toHtml}'s output as the text it shows, tags removed and entities
	 * read: what the adapter sends when Telegram refuses the HTML, so the person
	 * sees the words, not the markup.
	 */
	public static String htmlToPlain(String html) {
		if (html == null || html.isEmpty()) return "";
		return TAG.matcher(html).replaceAll("")
			.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
	}

	/** {@code &}, {@code <} and {@code >} as Telegram's HTML wants them in text. */
	static String escape(String text) {
		StringBuilder out = new StringBuilder(text.length() + 16);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '&' -> out.append("&amp;");
				case '<' -> out.append("&lt;");
				case '>' -> out.append("&gt;");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private static String escapeAttribute(String text) {
		return escape(text).replace("\"", "&quot;");
	}

	private static boolean visible(String html) {
		return !htmlToPlain(html).isBlank();
	}

	/** Blocks rendered as {@code pre}, which Telegram takes inside no other entity. */
	private static boolean preformatted(Node node) {
		return node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock || node instanceof TableBlock;
	}

	private static final class Renderer {

		/** The inline tags open around the point being written, outermost first: {name, opening tag}. */
		private final Deque<String[]> open = new ArrayDeque<>();
		/** Writing a quote's text, where Telegram takes no code, pre or link. */
		private boolean inQuote;

		/** The block children of {@code parent}, each rendered, joined by {@code separator}. */
		String blocks(Node parent, int quoteDepth, String separator) {
			List<String> parts = new ArrayList<>();
			for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
				String part = block(child, quoteDepth);
				if (visible(part)) parts.add(part);
			}
			return String.join(separator, parts);
		}

		String block(Node node, int quoteDepth) {
			if (node instanceof Paragraph) return inlines(node);
			if (node instanceof Heading) {
				StringBuilder out = new StringBuilder();
				wrap("b", "<b>", node, out);
				return out.toString();
			}
			if (node instanceof BulletList || node instanceof OrderedList) return list(node, 0, quoteDepth);
			if (node instanceof BlockQuote) {
				// Telegram refuses a quote inside a quote: only the outermost is a quote.
				return quoteDepth == 0 ? quote(node) : blocks(node, quoteDepth + 1, "\n");
			}
			if (node instanceof FencedCodeBlock fenced) return codeBlock(fenced.getLiteral(), fenced.getInfo());
			if (node instanceof IndentedCodeBlock indented) return codeBlock(indented.getLiteral(), null);
			if (node instanceof ThematicBreak) return RULE;
			if (node instanceof HtmlBlock html) return escape(trimEnd(html.getLiteral()));
			if (node instanceof TableBlock) return table(node);
			return blocks(node, quoteDepth, "\n\n");
		}

		/** A quote's text in one blockquote, nested quotes flattened into it; a code block or
		 *  table closes it and stands alone, since Telegram takes no pre inside a quote. */
		String quote(Node quote) {
			List<String> out = new ArrayList<>();
			List<String> text = new ArrayList<>();
			quoteParts(quote, text, out);
			endQuote(text, out);
			return String.join("\n", out);
		}

		private void quoteParts(Node quote, List<String> text, List<String> out) {
			for (Node child = quote.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof BlockQuote) {
					quoteParts(child, text, out);
				} else if (preformatted(child)) {
					endQuote(text, out);
					out.add(block(child, 1));
				} else {
					inQuote = true;
					String part;
					try {
						part = block(child, 1);
					} finally {
						inQuote = false;
					}
					if (visible(part)) text.add(part);
				}
			}
		}

		private static void endQuote(List<String> text, List<String> out) {
			if (text.isEmpty()) return;
			out.add("<blockquote>" + String.join("\n", text) + "</blockquote>");
			text.clear();
		}

		String list(Node list, int depth, int quoteDepth) {
			List<String> lines = new ArrayList<>();
			int number = list instanceof OrderedList ordered ? startOf(ordered) : 1;
			String indent = INDENT.repeat(depth);
			String hanging = indent + INDENT;
			for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
				if (!(item instanceof ListItem)) continue;
				String marker = list instanceof OrderedList ? (number++) + "." : BULLETS[Math.min(depth, BULLETS.length - 1)];
				StringBuilder entry = new StringBuilder(indent).append(marker).append(' ');
				boolean first = true;
				for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
					if (child instanceof BulletList || child instanceof OrderedList) {
						String nested = list(child, depth + 1, quoteDepth);
						if (visible(nested)) entry.append('\n').append(nested);
						first = false;
						continue;
					}
					String part = block(child, quoteDepth);
					if (!visible(part)) continue;
					if (preformatted(child) || child instanceof BlockQuote) {
						// Its own line, unindented: indentation inside a pre would change its text.
						entry.append('\n').append(part);
					} else {
						// A later paragraph, and every wrapped line, sits under the item's text.
						if (!first) entry.append('\n').append(hanging);
						entry.append(part.replace("\n", "\n" + hanging));
					}
					first = false;
				}
				lines.add(trimEnd(entry.toString()));
			}
			return String.join("\n", lines);
		}

		private static int startOf(OrderedList list) {
			Integer start = list.getMarkerStartNumber();
			return start == null ? 1 : start;
		}

		String codeBlock(String literal, String info) {
			String body = escape(trimEnd(literal == null ? "" : literal));
			if (inQuote) return body;   // nested in a quoted list: plain lines, as no pre fits there
			String language = info == null ? "" : info.strip().split("\\s+", 2)[0];
			if (!language.isEmpty() && LANGUAGE.matcher(language).matches()) {
				return "<pre><code class=\"language-" + language.toLowerCase(Locale.ROOT) + "\">" + body + "</code></pre>";
			}
			return "<pre>" + body + "</pre>";
		}

		/** A table as a monospaced grid: columns padded to their widest cell, the head ruled off. */
		String table(Node table) {
			List<List<String>> rows = new ArrayList<>();
			int headRows = 0;
			for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
				for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
					if (!(row instanceof TableRow)) continue;
					List<String> cells = new ArrayList<>();
					for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
						if (cell instanceof TableCell) cells.add(plain(cell).replace('\n', ' ').strip());
					}
					rows.add(cells);
					if (section instanceof TableHead) headRows++;
				}
			}
			int columns = rows.stream().mapToInt(List::size).max().orElse(0);
			int[] widths = new int[columns];
			for (List<String> row : rows) {
				for (int c = 0; c < row.size(); c++) widths[c] = Math.max(widths[c], width(row.get(c)));
			}
			List<String> lines = new ArrayList<>();
			for (int r = 0; r < rows.size(); r++) {
				List<String> row = rows.get(r);
				StringBuilder line = new StringBuilder();
				for (int c = 0; c < columns; c++) {
					String cell = c < row.size() ? row.get(c) : "";
					if (c > 0) line.append("  ");
					line.append(cell);
					if (c < columns - 1) line.append(" ".repeat(widths[c] - width(cell)));
				}
				lines.add(trimEnd(line.toString()));
				if (r == headRows - 1) {
					StringBuilder rule = new StringBuilder();
					for (int c = 0; c < columns; c++) {
						if (c > 0) rule.append("  ");
						rule.append("-".repeat(Math.max(1, widths[c])));
					}
					lines.add(rule.toString());
				}
			}
			String grid = escape(String.join("\n", lines));
			return inQuote ? grid : "<pre>" + grid + "</pre>";
		}

		private static int width(String text) {
			return text.codePointCount(0, text.length());
		}

		/** The inline children of {@code parent} in Telegram HTML. */
		String inlines(Node parent) {
			StringBuilder out = new StringBuilder();
			for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) inline(child, out);
			return out.toString();
		}

		void inline(Node node, StringBuilder out) {
			if (node instanceof Text text) {
				out.append(escape(text.getLiteral()));
			} else if (node instanceof StrongEmphasis) {
				wrap("b", "<b>", node, out);
			} else if (node instanceof Emphasis) {
				wrap("i", "<i>", node, out);
			} else if (node instanceof Strikethrough) {
				wrap("s", "<s>", node, out);
			} else if (node instanceof Code code) {
				inlineCode(code.getLiteral(), out);
			} else if (node instanceof Link link) {
				link(link.getDestination(), node, null, out);
			} else if (node instanceof Image image) {
				link(image.getDestination(), node, plain(node), out);
			} else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
				out.append('\n');
			} else if (node instanceof HtmlInline html) {
				out.append(escape(html.getLiteral()));
			} else {
				for (Node child = node.getFirstChild(); child != null; child = child.getNext()) inline(child, out);
			}
		}

		/** {@code node}'s inlines inside {@code <name>}, tracked so code can step out of it. */
		private void wrap(String name, String opening, Node node, StringBuilder out) {
			out.append(opening);
			open.addLast(new String[] { name, opening });
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) inline(child, out);
			open.removeLast();
			out.append("</").append(name).append('>');
		}

		private boolean inside(String name) {
			for (String[] tag : open) if (tag[0].equals(name)) return true;
			return false;
		}

		/** Code sits inside no other entity: the open formatting closes before it and reopens
		 *  after. Inside a link or a quote, which hold no code, it is plain text. */
		private void inlineCode(String literal, StringBuilder out) {
			if (inside("a") || inQuote) {
				out.append(escape(literal));
				return;
			}
			Iterator<String[]> outward = open.descendingIterator();
			while (outward.hasNext()) out.append("</").append(outward.next()[0]).append('>');
			out.append("<code>").append(escape(literal)).append("</code>");
			for (String[] tag : open) out.append(tag[1]);
		}

		/**
		 * A link (or an image, by its {@code alt} text): a link when Telegram opens its
		 * scheme and it is not already inside one, otherwise its text. With no text of
		 * its own it shows its destination.
		 */
		private void link(String destination, Node node, String altText, StringBuilder out) {
			boolean linked = openable(destination) && !inside("a") && !inQuote;
			String opening = linked ? "<a href=\"" + escapeAttribute(destination.strip()) + "\">" : null;
			StringBuilder label = new StringBuilder();
			if (linked) open.addLast(new String[] { "a", opening });
			if (altText != null) {
				label.append(escape(altText));
			} else {
				for (Node child = node.getFirstChild(); child != null; child = child.getNext()) inline(child, label);
			}
			if (linked) open.removeLast();
			if (!visible(label.toString()) && destination != null) {
				label.setLength(0);
				label.append(escape(destination));
			}
			if (linked) {
				out.append(opening).append(label).append("</a>");
			} else {
				out.append(label);
				// In a quote the address cannot be a link, so it is kept as text.
				if (inQuote && openable(destination) && !htmlToPlain(label.toString()).equals(destination.strip())) {
					out.append(" (").append(escape(destination.strip())).append(')');
				}
			}
		}

		private static boolean openable(String destination) {
			return destination != null && OPENABLE.matcher(destination.strip()).matches();
		}

		/** The text under {@code node} with no formatting, for table cells and image text. */
		private static String plain(Node node) {
			StringBuilder out = new StringBuilder();
			plain(node, out);
			return out.toString();
		}

		private static void plain(Node node, StringBuilder out) {
			for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
				if (child instanceof Text text) out.append(text.getLiteral());
				else if (child instanceof Code code) out.append(code.getLiteral());
				else if (child instanceof HtmlInline html) out.append(html.getLiteral());
				else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) out.append(' ');
				else plain(child, out);
			}
		}
	}

	private static String trimEnd(String text) {
		int end = text.length();
		while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
		return text.substring(0, end);
	}
}

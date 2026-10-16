package covia.adapter.telegram;

import java.util.ArrayList;
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
 * {@code span class="tg-spoiler"}, with newlines taken literally. A message
 * holding any other tag is refused, and the adapter then resends it as plain
 * text, tags and all, so a general Markdown renderer is the wrong tool. Every
 * string {@link #toHtml} returns is in that dialect:
 *
 * <ul>
 *   <li>bold, italic, strikethrough, inline code and links keep their meaning;
 *       a link whose scheme Telegram does not open keeps its text only;</li>
 *   <li>a heading becomes a bold line, a list item a bullet or numbered line
 *       (nested items indented), a block quote a {@code blockquote} (nested
 *       quotes flattened, which Telegram requires), a code block a
 *       {@code pre}, a table a monospaced {@code pre} grid, a rule a line of
 *       dashes;</li>
 *   <li>raw HTML in the source is shown as text, never passed through, and
 *       every {@code &}, {@code <} and {@code >} outside a tag is escaped.</li>
 * </ul>
 *
 * <p>A soft line break stays a newline. CommonMark renders it as a space, but a
 * model writing for a chat means the line it wrote, and Telegram shows newlines
 * as they are.
 *
 * <p>An inline tag crosses a line only where the source's own emphasis does, so
 * the adapter's long-message split, which prefers a line break, nearly always
 * keeps it whole. A part whose tags the cut separates, most often a {@code pre}
 * or {@code blockquote} spanning it, arrives as plain text.
 */
public final class TelegramMarkdown {

	/** The {@code parse_mode} under which {@link #toHtml}'s output is sent. */
	public static final String PARSE_MODE = "HTML";

	private static final List<Extension> EXTENSIONS =
		List.of(TablesExtension.create(), StrikethroughExtension.create());
	private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();

	/** Link schemes Telegram opens; anything else keeps its text and drops the link. */
	private static final Pattern OPENABLE = Pattern.compile("(?i)^(https?|tg|mailto):.+");
	/** A fence's language, as Telegram's {@code language-} class allows. */
	private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9_+#.-]{1,32}");
	private static final String[] BULLETS = { "•", "◦", "▪" };
	private static final String INDENT = "   ";
	private static final String RULE = "———";

	private TelegramMarkdown() {
	}

	/** Markdown to Telegram HTML; null or empty in, empty out. */
	public static String toHtml(String markdown) {
		if (markdown == null || markdown.isEmpty()) return "";
		Node document = PARSER.parse(markdown);
		return new Renderer().blocks(document, 0, "\n\n").strip();
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

	private static final class Renderer {

		/** The block children of {@code parent}, each rendered, joined by {@code separator}. */
		String blocks(Node parent, int quoteDepth, String separator) {
			List<String> parts = new ArrayList<>();
			for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
				String part = block(child, quoteDepth);
				if (!part.isBlank()) parts.add(part);
			}
			return String.join(separator, parts);
		}

		String block(Node node, int quoteDepth) {
			if (node instanceof Paragraph) return inlines(node);
			if (node instanceof Heading) return "<b>" + inlines(node) + "</b>";
			if (node instanceof BulletList || node instanceof OrderedList) return list(node, 0, quoteDepth);
			if (node instanceof BlockQuote) {
				String inner = blocks(node, quoteDepth + 1, "\n");
				// Telegram refuses a quote inside a quote: only the outermost is tagged.
				return quoteDepth == 0 ? "<blockquote>" + inner + "</blockquote>" : inner;
			}
			if (node instanceof FencedCodeBlock fenced) return code(fenced.getLiteral(), fenced.getInfo());
			if (node instanceof IndentedCodeBlock indented) return code(indented.getLiteral(), null);
			if (node instanceof ThematicBreak) return RULE;
			if (node instanceof HtmlBlock html) return escape(trimEnd(html.getLiteral()));
			if (node instanceof TableBlock) return table(node);
			return blocks(node, quoteDepth, "\n\n");
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
						if (!nested.isBlank()) entry.append('\n').append(nested);
						first = false;
						continue;
					}
					String part = block(child, quoteDepth);
					if (part.isBlank()) continue;
					// A later paragraph, and every wrapped line, sits under the item's text.
					if (!first) entry.append('\n').append(hanging);
					entry.append(part.replace("\n", "\n" + hanging));
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

		String code(String literal, String info) {
			String body = escape(trimEnd(literal == null ? "" : literal));
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
			return "<pre>" + escape(String.join("\n", lines)) + "</pre>";
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
				out.append("<b>").append(inlines(node)).append("</b>");
			} else if (node instanceof Emphasis) {
				out.append("<i>").append(inlines(node)).append("</i>");
			} else if (node instanceof Strikethrough) {
				out.append("<s>").append(inlines(node)).append("</s>");
			} else if (node instanceof Code code) {
				out.append("<code>").append(escape(code.getLiteral())).append("</code>");
			} else if (node instanceof Link link) {
				String label = inlines(node);
				if (openable(link.getDestination())) {
					out.append("<a href=\"").append(escapeAttribute(link.getDestination())).append("\">")
						.append(label.isBlank() ? escape(link.getDestination()) : label).append("</a>");
				} else {
					out.append(label);
				}
			} else if (node instanceof Image image) {
				String alt = plain(node);
				String label = escape(alt.isBlank() ? image.getDestination() : alt);
				if (openable(image.getDestination())) {
					out.append("<a href=\"").append(escapeAttribute(image.getDestination())).append("\">").append(label).append("</a>");
				} else {
					out.append(label);
				}
			} else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
				out.append('\n');
			} else if (node instanceof HtmlInline html) {
				out.append(escape(html.getLiteral()));
			} else {
				for (Node child = node.getFirstChild(); child != null; child = child.getNext()) inline(child, out);
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

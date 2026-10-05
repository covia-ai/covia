package covia.gui.markdown;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Document;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.LinkReferenceDefinition;
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
 * Renders Markdown (CommonMark plus GitHub tables and strikethrough) into a
 * {@link StyledDocument}: one pass over commonmark-java's AST, writing text
 * with character attributes for inline style and then laying each block out
 * with paragraph attributes. Reusable: parse once, render any number of times.
 *
 * <p>Structure is expressed only in what a styled document can carry. Blocks
 * are separated by a line break and spaced with {@code spaceAbove}; lists hang
 * their marker in a negative first-line indent measured from the marker's own
 * width; block quotes indent and mute; code takes the monospaced face and a
 * background; a table is a monospaced grid. A link's destination travels on
 * its text as the {@link #LINK} attribute.
 */
public final class MarkdownRenderer {
	/** Paragraph attribute used by MarkdownPane to keep table and code rows intact. */
	static final String NO_WRAP = "covia.markdown.noWrap";

	/**
	 * Character attribute on every run of link text: the destination as a
	 * {@link String}. A pane reads it back under the pointer.
	 */
	public static final Object LINK = new Object() {
		@Override
		public String toString() {
			return "markdown.link";
		}
	};

	private final MarkdownStyle style;
	private final Parser parser;

	public MarkdownRenderer(MarkdownStyle style) {
		this.style = Objects.requireNonNull(style, "style");
		this.parser = Parser.builder()
			.extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
			.build();
	}

	public MarkdownStyle style() {
		return style;
	}

	/** A fresh document holding the rendered {@code markdown} (null renders empty). */
	public StyledDocument render(String markdown) {
		DefaultStyledDocument doc = new DefaultStyledDocument();
		renderInto(doc, markdown);
		return doc;
	}

	/** Replaces {@code doc}'s content with the rendered {@code markdown}. */
	public void renderInto(StyledDocument doc, String markdown) {
		try {
			doc.remove(0, doc.getLength());
			new Writer(doc, style).write(parser.parse(markdown == null ? "" : markdown));
		} catch (BadLocationException e) {
			throw new IllegalStateException("offsets are the renderer's own", e);
		}
	}

	/** The text of a node's inline content, formatting dropped — table column widths. */
	static String plainText(Node node) {
		StringBuilder sb = new StringBuilder();
		AbstractVisitor collector = new AbstractVisitor() {
			@Override
			public void visit(Text text) {
				sb.append(text.getLiteral());
			}

			@Override
			public void visit(Code code) {
				sb.append(code.getLiteral());
			}

			@Override
			public void visit(SoftLineBreak softLineBreak) {
				sb.append(' ');
			}

			@Override
			public void visit(HardLineBreak hardLineBreak) {
				sb.append(' ');
			}
		};
		for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
			child.accept(collector);
		}
		return sb.toString();
	}

	/** One rendering pass. Not reusable: it carries the position in the document. */
	private static final class Writer extends AbstractVisitor {

		/** Measures as a text view lays out — whole-pixel advances — so a hung marker lands its text exactly on the indent. */
		private static final FontRenderContext MEASURE = new FontRenderContext(null, true, false);
		private static final String BULLETS = "•◦▪";
		private static final String RULE = "─".repeat(24);

		private final StyledDocument doc;
		private final MarkdownStyle style;
		/**
		 * Whether the monospaced face keeps the box-drawing glyphs on its
		 * character grid. A logical font often borrows them from another face at
		 * a different advance, and a table rule drawn in them then drifts off
		 * the columns; ASCII rules are the fallback.
		 */
		private final boolean boxDrawing;
		/** Inline attributes in force; text is inserted with the top. */
		private final Deque<SimpleAttributeSet> inline = new ArrayDeque<>();
		/** Enclosing lists, innermost first. */
		private final Deque<ListState> lists = new ArrayDeque<>();
		private int quoteDepth;
		/** The next block opened is the first of a list item, and carries its marker. */
		private boolean itemStart;

		/** A list being written: how its markers read and which number is next. */
		private static final class ListState {
			final boolean ordered;
			final String delimiter;
			final boolean tight;
			int next;

			ListState(boolean ordered, String delimiter, boolean tight, int first) {
				this.ordered = ordered;
				this.delimiter = delimiter;
				this.tight = tight;
				this.next = first;
			}

			String marker(int depth) {
				if (ordered) return (next++) + delimiter + " ";
				return BULLETS.charAt((depth - 1) % BULLETS.length()) + " ";
			}
		}

		/** A block being written: where it starts and how it will be laid out. */
		private record Block(int start, float indent, float hang, float gap) {
		}

		Writer(StyledDocument doc, MarkdownStyle style) {
			this.doc = doc;
			this.style = style;
			Font mono = style.monoFont();
			this.boxDrawing = sameAdvance(mono, "x", "─") && sameAdvance(mono, "x", "│")
				&& sameAdvance(mono, "x", "┼");
		}

		private static boolean sameAdvance(Font font, String a, String b) {
			return Math.abs(font.getStringBounds(a, MEASURE).getWidth()
				- font.getStringBounds(b, MEASURE).getWidth()) < 0.5;
		}

		void write(Node root) {
			inline.push(baseAttributes());
			root.accept(this);
		}

		// ------------------------------------------------------------------
		// Blocks
		// ------------------------------------------------------------------

		@Override
		public void visit(Document document) {
			visitChildren(document);
		}

		@Override
		public void visit(Paragraph paragraph) {
			Block b = openBlock();
			visitChildren(paragraph);
			closeBlock(b, 0, 0);
		}

		@Override
		public void visit(Heading heading) {
			Block b = openBlock();
			SimpleAttributeSet a = derive();
			StyleConstants.setBold(a, true);
			StyleConstants.setFontSize(a, Math.round(style.headingSize(heading.getLevel())));
			inline.push(a);
			visitChildren(heading);
			inline.pop();
			closeBlock(b, style.blockGap() / 2f, style.blockGap() / 4f);
		}

		@Override
		public void visit(BlockQuote blockQuote) {
			quoteDepth++;
			SimpleAttributeSet a = derive();
			StyleConstants.setForeground(a, style.muted());
			inline.push(a);
			visitChildren(blockQuote);
			inline.pop();
			quoteDepth--;
		}

		@Override
		public void visit(BulletList list) {
			lists.push(new ListState(false, "", list.isTight(), 1));
			visitChildren(list);
			lists.pop();
		}

		@Override
		public void visit(OrderedList list) {
			Integer first = list.getMarkerStartNumber();
			String delimiter = list.getMarkerDelimiter();
			lists.push(new ListState(true, (delimiter != null) ? delimiter : ".", list.isTight(),
				(first != null) ? first : 1));
			visitChildren(list);
			lists.pop();
		}

		@Override
		public void visit(ListItem item) {
			itemStart = true;
			visitChildren(item);
			if (itemStart) {
				// An empty item still shows its marker.
				closeBlock(openBlock(), 0, 0);
			}
		}

		@Override
		public void visit(FencedCodeBlock block) {
			codeBlock(block.getLiteral());
		}

		@Override
		public void visit(IndentedCodeBlock block) {
			codeBlock(block.getLiteral());
		}

		private void codeBlock(String literal) {
			Block b = openBlock();
			String text = (literal != null) ? literal : "";
			if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
			insert(text, code());
			closeBlock(b, 0, 0);
			noWrap(b);
		}

		@Override
		public void visit(ThematicBreak thematicBreak) {
			Block b = openBlock();
			SimpleAttributeSet a = derive();
			StyleConstants.setForeground(a, style.muted());
			insert(RULE, a);
			closeBlock(b, 0, 0);
		}

		@Override
		public void visit(HtmlBlock htmlBlock) {
			// Shown as what it is: there is no HTML engine here.
			Block b = openBlock();
			insert(htmlBlock.getLiteral().strip(), inline.peek());
			closeBlock(b, 0, 0);
		}

		@Override
		public void visit(LinkReferenceDefinition definition) {
			// A definition is consumed by the links that use it; it shows nothing.
		}

		@Override
		public void visit(CustomBlock customBlock) {
			if (customBlock instanceof TableBlock table) {
				table(table);
			} else {
				visitChildren(customBlock);
			}
		}

		// ------------------------------------------------------------------
		// Inline
		// ------------------------------------------------------------------

		@Override
		public void visit(Text text) {
			insert(text.getLiteral(), inline.peek());
		}

		@Override
		public void visit(SoftLineBreak softLineBreak) {
			insert(" ", inline.peek());
		}

		@Override
		public void visit(HardLineBreak hardLineBreak) {
			insert("\n", inline.peek());
		}

		@Override
		public void visit(Emphasis emphasis) {
			SimpleAttributeSet a = derive();
			StyleConstants.setItalic(a, true);
			inline.push(a);
			visitChildren(emphasis);
			inline.pop();
		}

		@Override
		public void visit(StrongEmphasis strongEmphasis) {
			SimpleAttributeSet a = derive();
			StyleConstants.setBold(a, true);
			inline.push(a);
			visitChildren(strongEmphasis);
			inline.pop();
		}

		@Override
		public void visit(Code code) {
			insert(code.getLiteral(), code());
		}

		@Override
		public void visit(Link link) {
			inline.push(link(link.getDestination()));
			visitChildren(link);
			inline.pop();
		}

		@Override
		public void visit(Image image) {
			// No images in a text document: the alt text, linked to the source.
			inline.push(link(image.getDestination()));
			String alt = plainText(image);
			insert(alt.isBlank() ? "image" : alt, inline.peek());
			inline.pop();
		}

		@Override
		public void visit(HtmlInline htmlInline) {
			insert(htmlInline.getLiteral(), inline.peek());
		}

		@Override
		public void visit(CustomNode customNode) {
			if (customNode instanceof Strikethrough) {
				SimpleAttributeSet a = derive();
				StyleConstants.setStrikeThrough(a, true);
				inline.push(a);
				visitChildren(customNode);
				inline.pop();
			} else {
				visitChildren(customNode);
			}
		}

		// ------------------------------------------------------------------
		// Tables: a monospaced grid, columns padded to their widest cell
		// ------------------------------------------------------------------

		private void table(TableBlock table) {
			List<List<TableCell>> rows = new ArrayList<>();
			for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
				for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
					List<TableCell> cells = new ArrayList<>();
					for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
						if (cell instanceof TableCell c) cells.add(c);
					}
					rows.add(cells);
				}
			}
			int columns = rows.stream().mapToInt(List::size).max().orElse(0);
			int[] widths = new int[columns];
			for (List<TableCell> row : rows) {
				for (int c = 0; c < row.size(); c++) {
					widths[c] = Math.max(widths[c], plainText(row.get(c)).length());
				}
			}

			String gap = boxDrawing ? " │ " : " | ";
			String joint = boxDrawing ? "─┼─" : "-+-";
			String fill = boxDrawing ? "─" : "-";

			Block b = openBlock();
			SimpleAttributeSet mono = code();
			mono.removeAttribute(StyleConstants.Background);
			SimpleAttributeSet frame = new SimpleAttributeSet(mono);
			StyleConstants.setForeground(frame, style.muted());
			boolean first = true;
			for (List<TableCell> row : rows) {
				if (!first) insert("\n", mono);
				first = false;
				boolean header = !row.isEmpty() && row.get(0).isHeader();
				for (int c = 0; c < columns; c++) {
					if (c > 0) insert(gap, frame);
					TableCell cell = (c < row.size()) ? row.get(c) : null;
					int pad = widths[c] - ((cell != null) ? plainText(cell).length() : 0);
					TableCell.Alignment align = (cell != null) ? cell.getAlignment() : null;
					int before = (align == TableCell.Alignment.RIGHT) ? pad
						: (align == TableCell.Alignment.CENTER) ? pad / 2 : 0;
					insert(" ".repeat(before), mono);
					if (cell != null) {
						SimpleAttributeSet cellAttrs = new SimpleAttributeSet(mono);
						if (header) StyleConstants.setBold(cellAttrs, true);
						inline.push(cellAttrs);
						visitChildren(cell);
						inline.pop();
					}
					insert(" ".repeat(pad - before), mono);
				}
				if (header) {
					insert("\n", mono);
					StringBuilder rule = new StringBuilder();
					for (int c = 0; c < columns; c++) {
						if (c > 0) rule.append(joint);
						rule.append(fill.repeat(widths[c]));
					}
					insert(rule.toString(), frame);
				}
			}
			closeBlock(b, 0, 0);
			noWrap(b);
		}

		// ------------------------------------------------------------------
		// Document plumbing
		// ------------------------------------------------------------------

		/**
		 * Starts a block: a line break after whatever came before, then the list
		 * marker if this is an item's first block. Returns what {@link #closeBlock}
		 * needs to lay the block out.
		 */
		private Block openBlock() {
			if (doc.getLength() > 0) insert("\n", inline.peek());
			int start = doc.getLength();
			float gap = style.blockGap();
			float hang = 0;
			if (itemStart) {
				itemStart = false;
				ListState list = lists.peek();
				if (list.tight) gap = style.blockGap() / 4f;
				String marker = list.marker(lists.size());
				insert(marker, inline.peek());
				hang = (float) style.font().getStringBounds(marker, MEASURE).getWidth();
			}
			return new Block(start, quoteDepth * style.quoteIndent() + lists.size() * style.listIndent(), hang, gap);
		}

		/**
		 * Lays out the lines written since the block opened: every line shares
		 * the indent; only the first carries the gap above and the marker hang,
		 * and only the last any space below.
		 */
		private void closeBlock(Block b, float extraAbove, float below) {
			int length = Math.max(0, doc.getLength() - b.start());
			SimpleAttributeSet all = new SimpleAttributeSet();
			all.addAttribute(NO_WRAP, false);
			StyleConstants.setLeftIndent(all, b.indent());
			StyleConstants.setFirstLineIndent(all, 0);
			StyleConstants.setSpaceAbove(all, 0);
			StyleConstants.setSpaceBelow(all, 0);
			doc.setParagraphAttributes(b.start(), length, all, false);

			SimpleAttributeSet first = new SimpleAttributeSet();
			StyleConstants.setFirstLineIndent(first, -b.hang());
			StyleConstants.setSpaceAbove(first, (b.start() > 0) ? b.gap() + extraAbove : 0);
			doc.setParagraphAttributes(b.start(), 1, first, false);

			if (below > 0) {
				SimpleAttributeSet last = new SimpleAttributeSet();
				StyleConstants.setSpaceBelow(last, below);
				doc.setParagraphAttributes(Math.max(b.start(), doc.getLength() - 1), 1, last, false);
			}
		}

		private void insert(String text, SimpleAttributeSet attributes) {
			if (text == null || text.isEmpty()) return;
			try {
				doc.insertString(doc.getLength(), text, attributes);
			} catch (BadLocationException e) {
				throw new IllegalStateException("appending cannot be out of bounds", e);
			}
		}

		private SimpleAttributeSet baseAttributes() {
			SimpleAttributeSet a = new SimpleAttributeSet();
			StyleConstants.setFontFamily(a, style.font().getFamily());
			StyleConstants.setFontSize(a, style.font().getSize());
			StyleConstants.setBold(a, style.font().isBold());
			StyleConstants.setItalic(a, style.font().isItalic());
			StyleConstants.setForeground(a, style.foreground());
			return a;
		}

		/** A copy of the attributes in force, to vary. */
		private SimpleAttributeSet derive() {
			return new SimpleAttributeSet(inline.peek());
		}

		private void noWrap(Block block) {
			SimpleAttributeSet a = new SimpleAttributeSet();
			a.addAttribute(NO_WRAP, true);
			doc.setParagraphAttributes(block.start(), doc.getLength() - block.start(), a, false);
		}

		/** The attributes in force, in the monospaced face on the code ground. */
		private SimpleAttributeSet code() {
			SimpleAttributeSet a = derive();
			StyleConstants.setFontFamily(a, style.monoFont().getFamily());
			StyleConstants.setFontSize(a, style.monoFont().getSize());
			if (style.codeBackground() != null) StyleConstants.setBackground(a, style.codeBackground());
			return a;
		}

		private SimpleAttributeSet link(String destination) {
			SimpleAttributeSet a = derive();
			StyleConstants.setForeground(a, style.link());
			StyleConstants.setUnderline(a, true);
			if (destination != null) a.addAttribute(LINK, destination);
			return a;
		}
	}
}

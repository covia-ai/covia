package covia.gui.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JTextPane;
import javax.swing.text.AttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * The AST → StyledDocument transform, checked on the document itself: the
 * text that comes out and the attributes on it — never a rendered picture.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MarkdownRendererTest {

	private static final MarkdownStyle STYLE = new MarkdownStyle(
			new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 14),
			new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13),
			java.awt.Color.BLACK, java.awt.Color.GRAY, java.awt.Color.BLUE, java.awt.Color.LIGHT_GRAY,
			8, 22, 18, new float[] {1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f});
	private static final MarkdownRenderer RENDERER = new MarkdownRenderer(STYLE);

	@Test
	void inlineStyleBecomesCharacterAttributes() throws Exception {
		StyledDocument d = RENDERER.render("plain **bold** *em* `code` ~~gone~~");
		assertEquals("plain bold em code gone", text(d));
		assertFalse(StyleConstants.isBold(at(d, "plain")));
		assertTrue(StyleConstants.isBold(at(d, "bold")));
		assertTrue(StyleConstants.isItalic(at(d, "em")));
		assertEquals(STYLE.monoFont().getFamily(), StyleConstants.getFontFamily(at(d, "code")));
		assertEquals(STYLE.codeBackground(), StyleConstants.getBackground(at(d, "code")));
		assertNull(at(d, "plain").getAttribute(StyleConstants.Background), "only code has a ground");
		assertTrue(StyleConstants.isStrikeThrough(at(d, "gone")));
		assertEquals(STYLE.font().getFamily(), StyleConstants.getFontFamily(at(d, "plain")));
		assertEquals(STYLE.foreground(), StyleConstants.getForeground(at(d, "plain")));
	}

	@Test
	void blocksAreLinesSpacedApart() throws Exception {
		StyledDocument d = RENDERER.render("first\n\nsecond");
		assertEquals("first\nsecond", text(d));
		assertEquals(0f, StyleConstants.getSpaceAbove(paragraphAt(d, "first")), "nothing above the first block");
		assertEquals((float) STYLE.blockGap(), StyleConstants.getSpaceAbove(paragraphAt(d, "second")));
	}

	@Test
	void lineBreaksStayInsideTheirBlock() throws Exception {
		StyledDocument d = RENDERER.render("soft\nbreak  \nhard");
		assertEquals("soft break\nhard", text(d));
		assertEquals(0f, StyleConstants.getSpaceAbove(paragraphAt(d, "hard")), "a continuation line has no gap");
	}

	@Test
	void headingsAreLargerAndBold() throws Exception {
		StyledDocument d = RENDERER.render("# Title\n\n## Sub\n\nbody");
		assertEquals("Title\nSub\nbody", text(d));
		assertTrue(StyleConstants.isBold(at(d, "Title")));
		assertEquals(Math.round(STYLE.headingSize(1)), StyleConstants.getFontSize(at(d, "Title")));
		assertEquals(Math.round(STYLE.headingSize(2)), StyleConstants.getFontSize(at(d, "Sub")));
		assertEquals(STYLE.font().getSize(), StyleConstants.getFontSize(at(d, "body")));
		assertFalse(StyleConstants.isBold(at(d, "body")));
	}

	@Test
	void listsCarryMarkersAndHangThem() throws Exception {
		StyledDocument d = RENDERER.render("- one\n- two\n  - deep\n\n3. third\n4. fourth");
		assertEquals("• one\n• two\n◦ deep\n3. third\n4. fourth", text(d));
		AttributeSet one = paragraphAt(d, "one");
		assertEquals((float) STYLE.listIndent(), StyleConstants.getLeftIndent(one));
		assertTrue(StyleConstants.getFirstLineIndent(one) < 0, "the marker hangs into the indent");
		assertEquals(2f * STYLE.listIndent(), StyleConstants.getLeftIndent(paragraphAt(d, "deep")));
		assertTrue(StyleConstants.getFirstLineIndent(paragraphAt(d, "fourth")) < 0);
	}

	@Test
	void listTextAlignsAtTheIndentWhateverTheMarkerWidth() throws Exception {
		onEdt(() -> {
			JTextPane pane = new JTextPane();
			pane.setStyledDocument(RENDERER.render("- one\n- two\n\n10. ten"));
			pane.setSize(400, 300);
			String t = text(pane.getStyledDocument());
			double marker = pane.modelToView2D(0).getX();
			double one = pane.modelToView2D(t.indexOf("one")).getX();
			double two = pane.modelToView2D(t.indexOf("two")).getX();
			double ten = pane.modelToView2D(t.indexOf("ten")).getX();
			assertTrue(marker < one, "the marker sits before the text");
			assertEquals(pane.getInsets().left + STYLE.listIndent(), one, 1.0, "item text starts at the list indent");
			assertEquals(one, two, 0.5, "items line up");
			assertEquals(one, ten, 1.0, "a wider marker still lands the text on the indent");
		});
	}

	@Test
	void quotesIndentAndMute() throws Exception {
		StyledDocument d = RENDERER.render("> quoted **loudly**\n\nafter");
		assertEquals("quoted loudly\nafter", text(d));
		assertEquals((float) STYLE.quoteIndent(), StyleConstants.getLeftIndent(paragraphAt(d, "quoted")));
		assertEquals(STYLE.muted(), StyleConstants.getForeground(at(d, "quoted")));
		assertTrue(StyleConstants.isBold(at(d, "loudly")), "inline style survives inside a quote");
		assertEquals(0f, StyleConstants.getLeftIndent(paragraphAt(d, "after")), "the indent ends with the quote");
		assertEquals(STYLE.foreground(), StyleConstants.getForeground(at(d, "after")));
	}

	@Test
	void codeBlocksKeepTheirLinesInTheMonospacedFace() throws Exception {
		StyledDocument d = RENDERER.render("```java\nint x;\nint y;\n```\n\ntext");
		assertEquals("int x;\nint y;\ntext", text(d));
		assertEquals(STYLE.monoFont().getFamily(), StyleConstants.getFontFamily(at(d, "int y")));
		assertEquals(STYLE.codeBackground(), StyleConstants.getBackground(at(d, "int y")));
		assertEquals(0f, StyleConstants.getSpaceAbove(paragraphAt(d, "int y")), "lines of one block are not spaced");
		assertEquals((float) STYLE.blockGap(), StyleConstants.getSpaceAbove(paragraphAt(d, "text")));
	}

	@Test
	void linksCarryTheirDestination() throws Exception {
		StyledDocument d = RENDERER.render("see [Covia](https://covia.ai) now");
		assertEquals("see Covia now", text(d));
		AttributeSet link = at(d, "Covia");
		assertEquals("https://covia.ai", link.getAttribute(MarkdownRenderer.LINK));
		assertTrue(StyleConstants.isUnderline(link));
		assertEquals(STYLE.link(), StyleConstants.getForeground(link));
		assertNull(at(d, "now").getAttribute(MarkdownRenderer.LINK));
	}

	@Test
	void imagesShowTheirAltTextAsALink() throws Exception {
		StyledDocument d = RENDERER.render("![a chart](https://x/chart.png)");
		assertEquals("a chart", text(d));
		assertEquals("https://x/chart.png", at(d, "a chart").getAttribute(MarkdownRenderer.LINK));
	}

	@Test
	void tablesAlignColumnsInTheMonospacedFace() throws Exception {
		StyledDocument d = RENDERER.render("| a | bb |\n|---|---:|\n| ccc | d |");
		String[] lines = text(d).split("\n");
		assertEquals(3, lines.length, text(d));
		int divider = indexOfAny(lines[0], "│|");
		assertTrue(divider > 0, lines[0]);
		assertEquals(divider, indexOfAny(lines[1], "┼+"), "the rule crosses under the divider");
		assertEquals(divider, indexOfAny(lines[2], "│|"), "every row divides in the same column");
		assertTrue(lines[2].endsWith(" d"), "a right-aligned cell pads on the left: " + lines[2]);
		assertTrue(StyleConstants.isBold(at(d, "bb")), "header cells are bold");
		assertFalse(StyleConstants.isBold(at(d, "ccc")));
		assertEquals(STYLE.monoFont().getFamily(), StyleConstants.getFontFamily(at(d, "ccc")));
	}

	@Test
	void tableRulesLandOnTheColumnsInPixels() throws Exception {
		// A logical monospaced font borrows box-drawing glyphs from another face
		// at a different advance; the renderer must notice and keep the grid.
		onEdt(() -> {
			JTextPane pane = new JTextPane();
			pane.setStyledDocument(RENDERER.render("| Op | readOnly | Notes |\n|---|:---:|---|\n| memory | no | tool |"));
			pane.setSize(600, 300);
			String t = text(pane.getStyledDocument());
			String[] lines = t.split("\n");
			int lineStart = 0;
			double[] dividerX = new double[3];
			for (int i = 0; i < 3; i++) {
				int col = indexOfAny(lines[i], (i == 1) ? "┼+" : "│|");
				assertTrue(col > 0, lines[i]);
				dividerX[i] = pane.modelToView2D(lineStart + col).getX();
				lineStart += lines[i].length() + 1;
			}
			assertEquals(dividerX[0], dividerX[1], 0.5, "the rule's joint sits under the header's divider");
			assertEquals(dividerX[0], dividerX[2], 0.5, "and the body's divider under both");
		});
	}

	@FunctionalInterface
	private interface EdtAction { void run() throws Exception; }

	private static void onEdt(EdtAction action) throws Exception {
		java.awt.EventQueue.invokeAndWait(() -> {
			try { action.run(); }
			catch (Exception failure) { throw new AssertionError(failure); }
		});
	}

	private static int indexOfAny(String s, String any) {
		for (int i = 0; i < s.length(); i++) {
			if (any.indexOf(s.charAt(i)) >= 0) return i;
		}
		return -1;
	}

	@Test
	void aRuleIsAMutedLine() throws Exception {
		StyledDocument d = RENDERER.render("above\n\n---\n\nbelow");
		String[] lines = text(d).split("\n");
		assertEquals(3, lines.length);
		assertTrue(lines[1].chars().allMatch(c -> c == '─'), lines[1]);
		assertEquals(STYLE.muted(), StyleConstants.getForeground(at(d, "─")));
	}

	@Test
	void nothingRendersAsNothing() throws Exception {
		assertEquals("", text(RENDERER.render(null)));
		assertEquals("", text(RENDERER.render("")));
		StyledDocument reused = RENDERER.render("old");
		RENDERER.renderInto(reused, "new");
		assertEquals("new", text(reused), "rendering into a document replaces its content");
	}

	private static String text(StyledDocument d) throws Exception {
		return d.getText(0, d.getLength());
	}

	private static AttributeSet at(StyledDocument d, String needle) throws Exception {
		int i = text(d).indexOf(needle);
		assertTrue(i >= 0, "'" + needle + "' in: " + text(d));
		return d.getCharacterElement(i).getAttributes();
	}

	private static AttributeSet paragraphAt(StyledDocument d, String needle) throws Exception {
		int i = text(d).indexOf(needle);
		assertTrue(i >= 0, "'" + needle + "' in: " + text(d));
		return d.getParagraphElement(i).getAttributes();
	}
}

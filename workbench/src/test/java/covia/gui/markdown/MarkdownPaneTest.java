package covia.gui.markdown;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.text.StyleConstants;

import org.junit.jupiter.api.Test;

import covia.gui.components.TextComponents;

class MarkdownPaneTest {

	private static MarkdownStyle style(Color foreground) {
		return new MarkdownStyle(new Font(Font.DIALOG, Font.PLAIN, 14),
				new Font(Font.MONOSPACED, Font.PLAIN, 13), foreground, foreground,
				foreground, null, 7, 24, 18, new float[] {1.4f, 1.3f, 1.2f, 1.1f, 1f, 1f});
	}

	@Test
	void hostOwnsAppearanceAndStyleRefresh() throws Exception {
		EventQueue.invokeAndWait(() -> {
			var lookAndFeel = UIManager.getLookAndFeel();
			var style = new AtomicReference<>(style(Color.RED));
			var pane = new MarkdownPane(style::get, "**Bold** and `code`");
			var border = BorderFactory.createEmptyBorder(3, 11, 5, 17);
			pane.setBorder(border);
			pane.setOpaque(true);
			pane.setBackground(Color.YELLOW);
			assertTrue(StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
			assertEquals(Color.RED, StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
			assertNull(pane.getStyledDocument().getCharacterElement(9).getAttributes().getAttribute(StyleConstants.Background));
			style.set(style(Color.GREEN));
			pane.updateUI();
			assertEquals(Color.GREEN, StyleConstants.getForeground(pane.getStyledDocument().getCharacterElement(0).getAttributes()));
			assertSame(lookAndFeel, UIManager.getLookAndFeel());
			assertSame(border, pane.getBorder());
			assertTrue(pane.isOpaque());
			assertEquals(Color.YELLOW, pane.getBackground());
			assertEquals("**Bold** and `code`", pane.getMarkdown());
			pane.setMarkdown(null);
			assertEquals(0, pane.getDocument().getLength());
		});
	}

	@Test
	void linksAreDeliveredOnlyToTheHostHandler() throws Exception {
		EventQueue.invokeAndWait(() -> {
			try {
				var pane = new MarkdownPane(() -> style(Color.BLACK), "[site](custom:target)");
				pane.setSize(300, 100);
				var bounds = pane.modelToView2D(1);
				var point = new Point((int) bounds.getX() + 1, (int) bounds.getCenterY());
				assertEquals("custom:target", pane.linkAt(point));
				var click = new MouseEvent(pane, MouseEvent.MOUSE_CLICKED, 0, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1);
				pane.dispatchEvent(click); // No handler: no browser or other side effect.
				var clicked = new AtomicReference<String>();
				pane.onLink(clicked::set);
				pane.dispatchEvent(click);
				assertEquals("custom:target", clicked.get());
				clicked.set(null);
				pane.select(0, 3);
				pane.dispatchEvent(click);
				assertNull(clicked.get(), "selecting text must not activate a link");
			} catch (Exception failure) { throw new AssertionError(failure); }
		});
	}

	@Test
	void wideTablesAndCodeKeepRowsIntactWhileOrdinaryParagraphsWrap() throws Exception {
		EventQueue.invokeAndWait(() -> {
			try {
				String table = "| Person | Responsibility |\n|---|---|\n| Taylor Reed | People operations and employee support |";
				String paragraph = "An ordinary paragraph with enough words to wrap at a narrow width. ".repeat(3);
				var pane = new MarkdownPane(() -> style(Color.BLACK), table + "\n\n" + paragraph + "\n\n```\n" + "long_code_".repeat(12) + "\n```");
				int natural = TextComponents.naturalWidth(pane);
				assertTrue(pane.getMinimumSize().width > 200, "the host can use this minimum to offer horizontal scrolling");
				var narrow = TextComponents.sizeForWidth(pane, 200);
				String text = pane.getDocument().getText(0, pane.getDocument().getLength());
				int rowStart = text.indexOf("Taylor Reed");
				int rowEnd = text.indexOf("support") + 6;
				assertEquals(pane.modelToView2D(rowStart).getY(), pane.modelToView2D(rowEnd).getY());
				int paragraphStart = text.indexOf("An ordinary");
				assertNotEquals(Boolean.TRUE, pane.getStyledDocument().getParagraphElement(paragraphStart)
						.getAttributes().getAttribute(MarkdownRenderer.NO_WRAP));
				var wide = TextComponents.sizeForWidth(pane, Math.max(800, natural));
				assertTrue(narrow.height > wide.height, "ordinary prose still reflows");
				assertEquals(natural, TextComponents.naturalWidth(pane), "measurement does not depend on the last viewport width");
			} catch (Exception failure) { throw new AssertionError(failure); }
		});
	}
}

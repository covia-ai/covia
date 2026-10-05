package covia.gui.components;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.EventQueue;

import javax.swing.BorderFactory;
import javax.swing.JTextArea;

import org.junit.jupiter.api.Test;

class TextComponentsTest {

	@Test
	void measuresPlainTextWithHostInsetsAndReflowsOnResize() throws Exception {
		EventQueue.invokeAndWait(() -> {
			var text = new JTextArea("A sentence with several words that should wrap at narrow widths.");
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			text.setBorder(BorderFactory.createEmptyBorder(7, 13, 11, 19));
			int expected = text.getFontMetrics(text.getFont()).stringWidth(text.getText()) + 32;
			assertEquals(expected, TextComponents.naturalWidth(text));
			var narrow = TextComponents.sizeForWidth(text, 120);
			var wide = TextComponents.sizeForWidth(text, expected + 6);
			assertTrue(narrow.height > wide.height);
			assertEquals(expected, TextComponents.naturalWidth(text));
			assertEquals(120, narrow.width);
			assertThrows(IllegalArgumentException.class, () -> TextComponents.sizeForWidth(text, 0));
		});
	}
}

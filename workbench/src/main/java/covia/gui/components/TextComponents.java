package covia.gui.components;

import java.awt.Dimension;

import javax.swing.JTextArea;
import javax.swing.text.JTextComponent;
import javax.swing.text.View;

/** Text measurement for host-owned layouts. Call on the Swing event thread. */
public final class TextComponents {

	private TextComponents() {}

	/** Unwrapped preferred width, including the component's own border and margin. */
	public static int naturalWidth(JTextComponent text) {
		var insets = text.getInsets();
		int width;
		if (text instanceof JTextArea) {
			var metrics = text.getFontMetrics(text.getFont());
			width = 0;
			for (String line : text.getText().split("\\R", -1)) width = Math.max(width, metrics.stringWidth(line));
		} else {
			width = (int) Math.ceil(text.getUI().getRootView(text).getPreferredSpan(View.X_AXIS));
		}
		return width + insets.left + insets.right;
	}

	/**
	 * Lay out at an exact outer width and return the required height. This changes
	 * the component's size; the host still owns its final bounds and wrapping policy.
	 */
	public static Dimension sizeForWidth(JTextComponent text, int width) {
		if (width <= 0) throw new IllegalArgumentException("width must be positive");
		text.setSize(width, Integer.MAX_VALUE);
		Dimension measured = new Dimension(width, text.getPreferredSize().height);
		text.setSize(measured);
		return measured;
	}
}

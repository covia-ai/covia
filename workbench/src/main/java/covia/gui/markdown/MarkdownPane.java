package covia.gui.markdown;

import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

/**
 * A read-only {@link JTextPane} showing rendered Markdown. Give it a style
 * source rather than a style: the text is rendered again with a fresh style
 * whenever the look and feel changes, so a theme switch carries through.
 * Links are inert until the host supplies {@link #onLink}. Text stays selectable
 * and copyable. Border, background, opacity and focus behaviour are ordinary
 * Swing properties owned by the host. Construct and update on the event thread.
 */
@SuppressWarnings("serial")
public class MarkdownPane extends JTextPane {

	private final Supplier<MarkdownStyle> style;
	private String markdown = "";
	private Consumer<String> linkHandler;
	private boolean built;

	public MarkdownPane(Supplier<MarkdownStyle> style) {
		this(style, "");
	}

	/**
	 * @param style    read at every render — pass the host's theme accessor
	 * @param markdown the initial text
	 */
	public MarkdownPane(Supplier<MarkdownStyle> style, String markdown) {
		this.style = Objects.requireNonNull(style, "style");
		setEditorKit(new MarkdownEditorKit());
		setEditable(false);
		MouseAdapter links = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) return;
				if (getSelectionStart() != getSelectionEnd()) return; // a selection, not a click
				String url = linkAt(e.getPoint());
				if (url != null && linkHandler != null) linkHandler.accept(url);
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				setCursor(Cursor.getPredefinedCursor(
					linkHandler != null && linkAt(e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
			}
		};
		addMouseListener(links);
		addMouseMotionListener(links);
		built = true;
		setMarkdown(markdown);
	}

	/** Renders {@code markdown} (null shows nothing), replacing what was shown. */
	public void setMarkdown(String markdown) {
		this.markdown = (markdown != null) ? markdown : "";
		render();
	}

	/** The source last given to {@link #setMarkdown}. */
	public String getMarkdown() {
		return markdown;
	}

	/** Host policy for link clicks. No URL is opened or fetched by this component. */
	public MarkdownPane onLink(Consumer<String> handler) {
		this.linkHandler = Objects.requireNonNull(handler, "handler");
		return this;
	}

	/**
	 * Never takes keyboard focus and shows no caret, but still paints its
	 * selection — for a transcript whose composer must keep the focus.
	 */
	public MarkdownPane unfocusable() {
		setFocusable(false);
		DefaultCaret caret = new DefaultCaret() {
			@Override
			public void setVisible(boolean visible) {
				super.setVisible(false);
			}

			@Override
			public void setSelectionVisible(boolean visible) {
				super.setSelectionVisible(true);
			}
		};
		caret.setBlinkRate(0);
		setCaret(caret);
		caret.setSelectionVisible(true);
		return this;
	}

	/** The destination of the link under {@code p}, or null when there is none. */
	public String linkAt(Point p) {
		StyledDocument doc = getStyledDocument();
		int pos = viewToModel2D(p);
		if (pos < 0 || pos >= doc.getLength()) return null;
		Element run = doc.getCharacterElement(pos);
		Object url = run.getAttributes().getAttribute(MarkdownRenderer.LINK);
		if (!(url instanceof String s)) return null;
		try {
			// The nearest position past a line's end is its last character:
			// only a pointer actually on the run counts.
			Rectangle2D at = modelToView2D(pos);
			if (at == null || p.getY() < at.getY() || p.getY() > at.getMaxY()) return null;
			Rectangle2D end = modelToView2D(Math.min(run.getEndOffset(), doc.getLength()));
			if (end != null && end.getY() == at.getY() && p.getX() > end.getX()) return null;
		} catch (BadLocationException e) {
			return null;
		}
		return s;
	}

	@Override
	public void updateUI() {
		super.updateUI();
		if (built) render();
	}

	private void render() {
		setStyledDocument(new MarkdownRenderer(style.get()).render(markdown));
	}

	/** Preserve code and table columns; a host can provide horizontal scrolling. */
	private static final class MarkdownEditorKit extends StyledEditorKit {
		@Override public ViewFactory getViewFactory() {
			ViewFactory standard = super.getViewFactory();
			return element -> {
				if (!AbstractDocument.ParagraphElementName.equals(element.getName())) return standard.create(element);
				return new ParagraphView(element) {
					private boolean noWrap() { return Boolean.TRUE.equals(getAttributes().getAttribute(MarkdownRenderer.NO_WRAP)); }
					@Override public float getMinimumSpan(int axis) {
						return axis == View.X_AXIS && noWrap() ? getPreferredSpan(axis) : super.getMinimumSpan(axis);
					}
					@Override public int getFlowSpan(int index) {
						return noWrap() ? Integer.MAX_VALUE / 2 : super.getFlowSpan(index);
					}
				};
			};
		}
	}
}

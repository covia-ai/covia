/**
 * Markdown rendering for Swing: commonmark-java's AST transformed into a
 * {@link javax.swing.text.StyledDocument}, shown in a
 * {@link covia.gui.markdown.MarkdownPane}.
 *
 * <p>Self-contained on purpose. This package depends on commonmark-java and the
 * JDK only. It does not install a look and feel, open links or fetch images.
 * A host describes how rendered text should look with a
 * {@link covia.gui.markdown.MarkdownStyle} built from its own theme (and
 * rebuilt when the theme changes); everything else is the package's business.
 *
 * <p>The transform is deliberately simple: one document, paragraph attributes
 * for structure (indent, spacing, hanging list markers), character attributes
 * for inline style, and a link's destination carried as the character attribute
 * {@link covia.gui.markdown.MarkdownRenderer#LINK}. Tables are laid out in the
 * monospaced face. The pane preserves table and code rows; the host can place
 * it in a scroll pane when those rows exceed the available width. Component
 * updates and sizing belong on the Swing event thread.
 */
package covia.gui.markdown;

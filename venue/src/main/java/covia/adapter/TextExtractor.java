package covia.adapter;

import java.io.IOException;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Readable text from a document — the seam behind {@code mode: "extract"}
 * on {@code file:read}, {@code vault:read} and {@code dlfs:read}.
 *
 * <p>The venue core carries no document parsers: a PDF or Office file read
 * as {@code text} is garbage and as {@code bytes} is useless to a model. The
 * optional <b>covia-documents</b> module registers a {@code documents}
 * adapter that implements this interface; the read surfaces find it through
 * {@link covia.venue.Engine#findAdapter(Class)} and, when no such adapter is
 * registered, fail naming the module rather than returning garbage. The
 * interface stays in core so the module needs nothing but the venue jar.</p>
 */
public interface TextExtractor {

	// Wire keys shared by every read surface and the documents:extract op
	AString K_TEXT       = Strings.intern("text");
	AString K_MIME       = Strings.intern("mime");
	AString K_SIZE       = Strings.intern("size");
	AString K_CHARS      = Strings.intern("chars");
	AString K_PAGES      = Strings.intern("pages");
	AString K_FIRST_PAGE = Strings.intern("firstPage");
	AString K_LAST_PAGE  = Strings.intern("lastPage");
	AString K_TRUNCATED  = Strings.intern("truncated");
	AString K_META       = Strings.intern("meta");
	AString K_MAX_CHARS  = Strings.intern("maxChars");
	AString K_FIRST      = Strings.intern("first");
	AString K_LAST       = Strings.intern("last");

	/**
	 * What to extract.
	 *
	 * @param bytes the document
	 * @param filename its name, for type detection and messages
	 * @param mime its detected type
	 * @param maxChars the character cap, or 0 for the extractor's default
	 * @param firstPage first page of a 1-based inclusive range, or null from the start
	 * @param lastPage last page of the range, or null to the end
	 */
	record Request(byte[] bytes, String filename, String mime, int maxChars,
			Integer firstPage, Integer lastPage) {}

	/**
	 * What was extracted.
	 *
	 * @param text the readable text, page or slide boundaries marked
	 * @param pages the page (or slide) count when the format is paged, else null
	 * @param firstPage the first page covered when paged, else null
	 * @param lastPage the last page covered — the page the cap cut into, when it did
	 * @param truncated true when the character cap cut the text
	 * @param meta document metadata — {@code title}, {@code author}, {@code created}
	 *             when known; {@code scanned} and a {@code note} when a paged
	 *             document has little or no text layer
	 */
	record Extraction(String text, Integer pages, Integer firstPage, Integer lastPage,
			boolean truncated, AMap<AString, ACell> meta) {

		/** The wire form every read surface returns for {@code mode: "extract"}. */
		public AMap<AString, ACell> toCell(String mime, long size) {
			AMap<AString, ACell> out = Maps.of(
				K_TEXT, Strings.create(text),
				K_MIME, Strings.create(mime),
				K_SIZE, CVMLong.create(size),
				K_CHARS, CVMLong.create(text.length()),
				K_TRUNCATED, CVMBool.create(truncated));
			if (pages != null) out = out.assoc(K_PAGES, CVMLong.create(pages));
			if (firstPage != null) out = out.assoc(K_FIRST_PAGE, CVMLong.create(firstPage));
			if (lastPage != null) out = out.assoc(K_LAST_PAGE, CVMLong.create(lastPage));
			if (meta != null && !meta.isEmpty()) out = out.assoc(K_META, meta);
			return out;
		}
	}

	/** True when this extractor can read the type — by detected MIME or by file extension. */
	boolean supports(String mime, String filename);

	/** The types this extractor reads, for a refusal message. */
	String supported();

	/**
	 * Extracts. Throws {@link IllegalArgumentException} for an unsupported or
	 * unreadable document (password-protected, malformed) with a message a
	 * caller can act on.
	 */
	Extraction extract(Request request) throws IOException;

	/**
	 * Parses a page selection: a page number, a range such as {@code "3-5"}
	 * (either end may be omitted), or {@code {first, last}}. Returns
	 * {@code {first, last}} with null for an open end, or null when absent.
	 */
	static Integer[] parsePages(ACell spec) {
		if (spec == null) return null;
		if (spec instanceof CVMLong n) return new Integer[] { (int) n.longValue(), (int) n.longValue() };
		if (spec instanceof AMap<?, ?>) {
			CVMLong first = RT.ensureLong(RT.getIn(spec, K_FIRST));
			CVMLong last = RT.ensureLong(RT.getIn(spec, K_LAST));
			return new Integer[] {
				(first != null) ? (int) first.longValue() : null,
				(last != null) ? (int) last.longValue() : null };
		}
		AString s = RT.ensureString(spec);
		String text = (s != null) ? s.toString().trim() : "";
		try {
			int dash = text.indexOf('-');
			if (s == null || text.isEmpty()) throw new NumberFormatException();
			if (dash < 0) {
				int n = Integer.parseInt(text);
				return new Integer[] { n, n };
			}
			String a = text.substring(0, dash).trim();
			String b = text.substring(dash + 1).trim();
			return new Integer[] {
				a.isEmpty() ? null : Integer.parseInt(a),
				b.isEmpty() ? null : Integer.parseInt(b) };
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(
				"pages must be a page number, a range like \"3-5\", or {first, last}; got: " + spec);
		}
	}
}

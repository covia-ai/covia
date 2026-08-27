package covia.adapter.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.extractor.ExcelExtractor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.sl.extractor.SlideShowExtractor;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.adapter.AAdapter;
import covia.adapter.TextExtractor;
import covia.utils.MimeUtils;
import covia.venue.RequestContext;

/**
 * Readable text from PDF and Office documents — the {@link TextExtractor}
 * behind {@code mode: "extract"} on every file read surface, packaged as the
 * optional covia-documents module so the parsers (PDFBox, POI) stay out of
 * {@code covia.jar}.
 *
 * <p><b>What it reads.</b> PDF (text layer, per page); DOCX, XLSX, PPTX and
 * their legacy DOC, XLS, PPT forms; plain text formats pass through. Pages
 * and slides are marked ({@code --- page 3 ---}) so a caller can cite and
 * re-read a slice; {@code pages} selects a range and {@code maxChars} caps
 * the text, reported as {@code truncated} with the last page covered — a
 * 300-page report is read in slices, never flooded into context.</p>
 *
 * <p><b>What it does not do.</b> No OCR: a PDF with little or no text layer
 * — a scanned letter — is reported as {@code scanned} with a note, rather
 * than returned as empty text that looks like an empty document. Images,
 * HEIC, RTF and ODF are not documents this module reads; the refusal names
 * the supported types.</p>
 */
public class DocumentsAdapter extends AAdapter implements TextExtractor {

	public static final Logger log = LoggerFactory.getLogger(DocumentsAdapter.class);

	public static final String NAME = "documents";

	/** Characters returned when a caller sets no cap. */
	public static final int DEFAULT_MAX_CHARS = 16_000;
	/** The most a caller may ask for, however the operator configures the default. */
	public static final int MAX_CHARS_CEILING = 1_000_000;
	/** A paged document averaging fewer characters per page than this has no usable text layer. */
	static final int SCANNED_CHARS_PER_PAGE = 20;

	static final AString K_MAX_CHARS_CONFIG = Strings.intern("maxChars");
	static final AString K_SUPPORTED = Strings.intern("supported");
	static final AString K_BYTES = Strings.intern("bytes");
	static final AString K_FILENAME = Strings.intern("filename");
	static final AString K_TITLE = Strings.intern("title");
	static final AString K_AUTHOR = Strings.intern("author");
	static final AString K_CREATED = Strings.intern("created");
	static final AString K_SCANNED = Strings.intern("scanned");
	static final AString K_NOTE = Strings.intern("note");
	static final AString K_SHEETS = Strings.intern("sheets");

	private static final String SCANNED_NOTE =
		"little or no text layer — likely a scanned document; OCR is not available, "
		+ "so read it as an image if the model can see one";

	/** The document kinds this module reads, by extension and by MIME type. */
	enum Kind {
		PDF("pdf", "application/pdf"),
		DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
		XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
		PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
		DOC("doc", "application/msword"),
		XLS("xls", "application/vnd.ms-excel"),
		PPT("ppt", "application/vnd.ms-powerpoint"),
		TEXT(null, null);

		final String extension;
		final String mime;
		Kind(String extension, String mime) { this.extension = extension; this.mime = mime; }
	}

	private static final Set<String> TEXT_EXTENSIONS = Set.of(
		"txt", "text", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log");

	private volatile int maxChars = DEFAULT_MAX_CHARS;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public String getDescription() {
		return "Readable text from PDF and Office documents (docx, xlsx, pptx and legacy doc, xls, ppt): "
			+ "the extractor behind mode 'extract' on file, vault and DLFS reads, with page ranges and a "
			+ "character cap so large documents are read in slices.";
	}

	@Override
	protected void installAssets() {
		installSkill("data/documents", "/skills/documents.json");
		installAsset("documents/extract", "/adapters/documents/extract.json");
	}

	// ========== Configuration ==========

	@Override
	public boolean configure(AMap<AString, ACell> config, boolean strict) {
		int cap = DEFAULT_MAX_CHARS;
		if (config != null && config.get(K_MAX_CHARS_CONFIG) != null) {
			CVMLong n = RT.ensureLong(config.get(K_MAX_CHARS_CONFIG));
			if (n == null || n.longValue() < 1 || n.longValue() > MAX_CHARS_CEILING) {
				throw new IllegalArgumentException("adapters.documents.maxChars must be an integer from 1 to "
					+ MAX_CHARS_CEILING + ", got: " + config.get(K_MAX_CHARS_CONFIG));
			}
			cap = (int) n.longValue();
		}
		maxChars = cap;
		return true;
	}

	@Override
	public AMap<AString, ACell> info() {
		AVector<ACell> types = Vectors.empty();
		for (Kind k : Kind.values()) {
			if (k.extension != null) types = types.conj(Strings.create(k.extension));
		}
		for (String t : List.of("txt", "md", "csv", "json", "xml", "yaml")) types = types.conj(Strings.create(t));
		return Maps.of(
			K_SUPPORTED, types,
			K_MAX_CHARS_CONFIG, CVMLong.create(maxChars));
	}

	/** The character cap applied when a caller sets none. */
	public int getMaxChars() {
		return maxChars;
	}

	// ========== TextExtractor ==========

	static Kind kindOf(String mime, String filename) {
		String ext = extension(filename);
		if (ext != null) {
			for (Kind k : Kind.values()) if (ext.equals(k.extension)) return k;
			if (TEXT_EXTENSIONS.contains(ext)) return Kind.TEXT;
		}
		if (mime != null) {
			String m = mime.toLowerCase(Locale.ROOT);
			int semi = m.indexOf(';');
			if (semi >= 0) m = m.substring(0, semi).trim();
			for (Kind k : Kind.values()) if (m.equals(k.mime)) return k;
			if (m.startsWith("text/") || m.equals("application/json") || m.equals("application/xml")
					|| m.equals("application/yaml")) return Kind.TEXT;
		}
		return null;
	}

	private static String extension(String filename) {
		if (filename == null) return null;
		int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
		String name = (slash >= 0) ? filename.substring(slash + 1) : filename;
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) return null;
		return name.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean supports(String mime, String filename) {
		return kindOf(mime, filename) != null;
	}

	@Override
	public String supported() {
		return "pdf, docx, xlsx, pptx, doc, xls, ppt, and plain text (txt, md, csv, json, xml, yaml)";
	}

	@Override
	public Extraction extract(Request request) throws IOException {
		Kind kind = kindOf(request.mime(), request.filename());
		if (kind == null) {
			throw new IllegalArgumentException("No text extractor for " + request.filename()
				+ " (" + request.mime() + "); supported: " + supported());
		}
		int cap = (request.maxChars() > 0) ? Math.min(request.maxChars(), MAX_CHARS_CEILING) : maxChars;
		try {
			return switch (kind) {
				case PDF -> pdf(request, cap);
				case DOCX -> docx(request.bytes(), cap);
				case XLSX -> xlsx(request.bytes(), cap);
				case PPTX -> pptx(request, cap);
				case DOC -> doc(request.bytes(), cap);
				case XLS -> xls(request.bytes(), cap);
				case PPT -> ppt(request, cap);
				case TEXT -> text(request.bytes(), cap);
			};
		} catch (InvalidPasswordException e) {
			throw new IllegalArgumentException(request.filename() + " is password-protected; it cannot be read");
		} catch (IOException | RuntimeException e) {
			if (e instanceof IllegalArgumentException iae) throw iae;
			String detail = (e.getMessage() != null && !e.getMessage().isBlank())
				? e.getMessage() : e.getClass().getSimpleName();
			throw new IllegalArgumentException("Cannot read " + request.filename()
				+ " as " + kind.name().toLowerCase(Locale.ROOT) + ": " + detail);
		}
	}

	// ========== PDF ==========

	private Extraction pdf(Request request, int cap) throws IOException {
		try (PDDocument doc = Loader.loadPDF(request.bytes())) {
			int pages = doc.getNumberOfPages();
			int first = clamp(request.firstPage() != null ? request.firstPage() : 1, 1, Math.max(pages, 1));
			int last = clamp(request.lastPage() != null ? request.lastPage() : pages, first, Math.max(pages, 1));
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			Paged paged = new Paged(cap, "page");
			for (int p = first; p <= last && pages > 0; p++) {
				stripper.setStartPage(p);
				stripper.setEndPage(p);
				if (!paged.add(p, stripper.getText(doc))) break;
			}
			AMap<AString, ACell> meta = Maps.empty();
			PDDocumentInformation info = doc.getDocumentInformation();
			if (info != null) {
				meta = put(meta, K_TITLE, info.getTitle());
				meta = put(meta, K_AUTHOR, info.getAuthor());
				Calendar created = info.getCreationDate();
				if (created != null) meta = meta.assoc(K_CREATED, CVMLong.create(created.getTimeInMillis()));
			}
			return paged.finish(pages, first, meta);
		}
	}

	// ========== Office (OOXML) ==========

	private Extraction docx(byte[] bytes, int cap) throws IOException {
		try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
			 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
			return flat(extractor.getText(), cap, coreMeta(doc.getProperties()));
		}
	}

	private Extraction xlsx(byte[] bytes, int cap) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
			 XSSFExcelExtractor extractor = new XSSFExcelExtractor(workbook)) {
			extractor.setIncludeSheetNames(true);
			AMap<AString, ACell> meta = coreMeta(workbook.getProperties())
				.assoc(K_SHEETS, CVMLong.create(workbook.getNumberOfSheets()));
			return flat(extractor.getText(), cap, meta);
		}
	}

	private Extraction pptx(Request request, int cap) throws IOException {
		try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(request.bytes()));
			 SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(show)) {
			extractor.setSlidesByDefault(true);
			extractor.setNotesByDefault(false);
			List<XSLFSlide> slides = show.getSlides();
			int pages = slides.size();
			int first = clamp(request.firstPage() != null ? request.firstPage() : 1, 1, Math.max(pages, 1));
			int last = clamp(request.lastPage() != null ? request.lastPage() : pages, first, Math.max(pages, 1));
			Paged paged = new Paged(cap, "slide");
			for (int p = first; p <= last && pages > 0; p++) {
				if (!paged.add(p, slideText(extractor, slides.get(p - 1)))) break;
			}
			return paged.finish(pages, first, coreMeta(show.getProperties()));
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static String slideText(SlideShowExtractor extractor, Object slide) {
		return extractor.getText((org.apache.poi.sl.usermodel.Slide) slide);
	}

	private static AMap<AString, ACell> coreMeta(POIXMLProperties properties) {
		AMap<AString, ACell> meta = Maps.empty();
		if (properties == null || properties.getCoreProperties() == null) return meta;
		POIXMLProperties.CoreProperties core = properties.getCoreProperties();
		meta = put(meta, K_TITLE, core.getTitle());
		meta = put(meta, K_AUTHOR, core.getCreator());
		Date created = core.getCreated();
		if (created != null) meta = meta.assoc(K_CREATED, CVMLong.create(created.getTime()));
		return meta;
	}

	// ========== Office (legacy binary) ==========

	private Extraction doc(byte[] bytes, int cap) throws IOException {
		try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
			 WordExtractor extractor = new WordExtractor(doc)) {
			AMap<AString, ACell> meta = Maps.empty();
			if (doc.getSummaryInformation() != null) {
				meta = put(meta, K_TITLE, doc.getSummaryInformation().getTitle());
				meta = put(meta, K_AUTHOR, doc.getSummaryInformation().getAuthor());
			}
			return flat(extractor.getText(), cap, meta);
		}
	}

	private Extraction xls(byte[] bytes, int cap) throws IOException {
		try (HSSFWorkbook workbook = new HSSFWorkbook(new ByteArrayInputStream(bytes));
			 ExcelExtractor extractor = new ExcelExtractor(workbook)) {
			extractor.setIncludeSheetNames(true);
			return flat(extractor.getText(), cap,
				Maps.of(K_SHEETS, CVMLong.create(workbook.getNumberOfSheets())));
		}
	}

	private Extraction ppt(Request request, int cap) throws IOException {
		try (HSLFSlideShow show = new HSLFSlideShow(new ByteArrayInputStream(request.bytes()));
			 SlideShowExtractor<?, ?> extractor = new SlideShowExtractor<>(show)) {
			extractor.setSlidesByDefault(true);
			extractor.setNotesByDefault(false);
			List<HSLFSlide> slides = show.getSlides();
			int pages = slides.size();
			int first = clamp(request.firstPage() != null ? request.firstPage() : 1, 1, Math.max(pages, 1));
			int last = clamp(request.lastPage() != null ? request.lastPage() : pages, first, Math.max(pages, 1));
			Paged paged = new Paged(cap, "slide");
			for (int p = first; p <= last && pages > 0; p++) {
				if (!paged.add(p, slideText(extractor, slides.get(p - 1)))) break;
			}
			return paged.finish(pages, first, Maps.empty());
		}
	}

	// ========== Plain text ==========

	private static Extraction text(byte[] bytes, int cap) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("File is not valid UTF-8 text");
		}
		return flat(text, cap, Maps.empty());
	}

	// ========== Shaping ==========

	/** Accumulates page (or slide) blocks under a cap, marking each. */
	private static final class Paged {
		private final StringBuilder sb = new StringBuilder();
		private final int cap;
		private final String unit;
		private int textChars;
		private int covered;
		private Integer lastPage;
		private boolean truncated;

		Paged(int cap, String unit) {
			this.cap = cap;
			this.unit = unit;
		}

		/** Adds one page; false when the cap has been reached. */
		boolean add(int page, String text) {
			String body = (text != null) ? text.strip() : "";
			String block = "--- " + unit + " " + page + " ---\n" + body + "\n\n";
			lastPage = page;
			if (sb.length() + block.length() > cap) {
				int room = cap - sb.length();
				if (room > 0) sb.append(block, 0, room);
				truncated = true;
				return false;
			}
			sb.append(block);
			textChars += body.length();
			covered++;
			return true;
		}

		Extraction finish(int pages, int first, AMap<AString, ACell> meta) {
			if (covered > 0 && textChars < SCANNED_CHARS_PER_PAGE * covered) {
				meta = meta.assoc(K_SCANNED, CVMBool.TRUE).assoc(K_NOTE, Strings.create(SCANNED_NOTE));
			}
			return new Extraction(sb.toString().strip(), pages,
				(pages > 0) ? first : null, (pages > 0) ? lastPage : null, truncated, meta);
		}
	}

	/** An unpaged document under the cap. */
	private static Extraction flat(String text, int cap, AMap<AString, ACell> meta) {
		String body = (text != null) ? text.strip() : "";
		boolean truncated = body.length() > cap;
		if (truncated) body = body.substring(0, cap);
		return new Extraction(body, null, null, null, truncated, meta);
	}

	private static AMap<AString, ACell> put(AMap<AString, ACell> meta, AString key, String value) {
		return (value != null && !value.isBlank()) ? meta.assoc(key, Strings.create(value.strip())) : meta;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	// ========== documents:extract ==========

	@Override
	public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
		requireInvoke(ctx);
		String subOp = getSubOperation(meta);
		if (!"extract".equals(subOp)) {
			return CompletableFuture.failedFuture(
				new IllegalArgumentException("Unknown documents operation: " + subOp));
		}
		try {
			return CompletableFuture.completedFuture(handleExtract(input));
		} catch (IOException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/**
	 * Extracts from bytes a caller already holds — an upload, a fetched
	 * body. Files on a root or drive are read with {@code file:read} /
	 * {@code vault:read} / {@code dlfs:read} and {@code mode: "extract"},
	 * which route here with their own authorisation.
	 */
	private ACell handleExtract(ACell input) throws IOException {
		AString encoded = RT.ensureString(RT.getIn(input, K_BYTES));
		if (encoded == null || encoded.isEmpty()) {
			throw new IllegalArgumentException("bytes (base64) is required");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(encoded.toString().trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("bytes must be base64: " + e.getMessage());
		}
		AString filenameCell = RT.ensureString(RT.getIn(input, K_FILENAME));
		String filename = (filenameCell != null) ? filenameCell.toString() : "document";
		AString mimeCell = RT.ensureString(RT.getIn(input, K_MIME));
		String mime = (mimeCell != null) ? mimeCell.toString() : MimeUtils.guess(filename, bytes);
		if (!supports(mime, filename)) {
			throw new IllegalArgumentException("No text extractor for " + filename
				+ " (" + mime + "); supported: " + supported());
		}
		CVMLong max = RT.ensureLong(RT.getIn(input, K_MAX_CHARS));
		Integer[] pages = TextExtractor.parsePages(RT.getIn(input, K_PAGES));
		Extraction extraction = extract(new Request(bytes, filename, mime,
			(max != null) ? (int) max.longValue() : 0,
			(pages != null) ? pages[0] : null, (pages != null) ? pages[1] : null));
		return extraction.toCell(mime, bytes.length);
	}
}

package covia.adapter.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.lang.RT;
import covia.adapter.TextExtractor;
import covia.adapter.TextExtractor.Extraction;
import covia.adapter.TextExtractor.Request;
import covia.grid.Job;
import covia.grid.Status;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * The documents module: PDF and Office text with pages marked, ranges and a
 * cap; scanned PDFs flagged; the extractor reached from {@code file:read}
 * {@code mode: "extract"} and from {@code documents:extract}.
 */
public class DocumentsAdapterTest {

	@TempDir Path temp;

	private final DocumentsAdapter adapter = new DocumentsAdapter();

	private static Request request(byte[] bytes, String name, int maxChars, Integer first, Integer last) {
		return new Request(bytes, name, null, maxChars, first, last);
	}

	// ========== PDF ==========

	@Test
	public void testPdfPagesMarkersMetadataAndRange() throws Exception {
		byte[] pdf = Fixtures.pdf("Quarterly Report", "Page one: revenue grew twelve percent this quarter.", "Page two: costs held flat despite inflation.", "Page three: outlook remains cautiously positive.");
		assertTrue(adapter.supports("application/pdf", "report.pdf"));

		Extraction all = adapter.extract(request(pdf, "report.pdf", 0, null, null));
		assertEquals(3, all.pages());
		assertEquals(1, all.firstPage());
		assertEquals(3, all.lastPage());
		assertFalse(all.truncated());
		assertTrue(all.text().contains("--- page 1 ---"), all.text());
		assertTrue(all.text().contains("Page one: revenue"), all.text());
		assertTrue(all.text().contains("--- page 3 ---") && all.text().contains("Page three: outlook"), all.text());
		assertEquals(Strings.create("Quarterly Report"), all.meta().get(DocumentsAdapter.K_TITLE));
		assertEquals(Strings.create("Covia Tests"), all.meta().get(DocumentsAdapter.K_AUTHOR));
		assertNull(all.meta().get(DocumentsAdapter.K_SCANNED));

		Extraction slice = adapter.extract(request(pdf, "report.pdf", 0, 2, 3));
		assertEquals(2, slice.firstPage());
		assertEquals(3, slice.lastPage());
		assertFalse(slice.text().contains("Page one"), slice.text());
		assertTrue(slice.text().startsWith("--- page 2 ---"), slice.text());

		// Out-of-range ends clamp rather than fail.
		Extraction clamped = adapter.extract(request(pdf, "report.pdf", 0, 3, 99));
		assertEquals(3, clamped.firstPage());
		assertEquals(3, clamped.lastPage());
	}

	@Test
	public void testCapTruncatesAndReportsHowFarItGot() throws Exception {
		byte[] pdf = Fixtures.pdf(null, "Page one: revenue grew twelve percent this quarter.", "Page two: costs held flat despite inflation.", "Page three: outlook remains cautiously positive.");
		Extraction cut = adapter.extract(request(pdf, "report.pdf", 100, null, null));
		assertTrue(cut.truncated());
		assertTrue(cut.text().length() <= 100, cut.text());
		assertEquals(3, cut.pages());
		assertEquals(2, cut.lastPage(), "the cap cut into page 2");

		AMap<AString, ACell> cell = cut.toCell("application/pdf", pdf.length);
		assertEquals(CVMBool.TRUE, cell.get(TextExtractor.K_TRUNCATED));
		assertEquals(2L, RT.ensureLong(cell.get(TextExtractor.K_LAST_PAGE)).longValue());
	}

	@Test
	public void testScannedPdfIsFlaggedNotSilentlyEmpty() throws Exception {
		byte[] scanned = Fixtures.pdf(null, "", "");
		Extraction x = adapter.extract(request(scanned, "letter.pdf", 0, null, null));
		assertEquals(2, x.pages());
		assertEquals(CVMBool.TRUE, x.meta().get(DocumentsAdapter.K_SCANNED));
		assertNotNull(x.meta().get(DocumentsAdapter.K_NOTE));
		assertTrue(x.text().contains("--- page 1 ---"), "pages are still marked: " + x.text());
	}

	// ========== Office ==========

	@Test
	public void testOfficeFormats() throws Exception {
		Extraction docx = adapter.extract(request(Fixtures.docx("Memo", "Hello from docx", "Second paragraph"),
			"memo.docx", 0, null, null));
		assertTrue(docx.text().contains("Hello from docx") && docx.text().contains("Second paragraph"), docx.text());
		assertEquals(Strings.create("Memo"), docx.meta().get(DocumentsAdapter.K_TITLE));
		assertNull(docx.pages());

		Extraction xlsx = adapter.extract(request(Fixtures.xlsx(), "figures.xlsx", 0, null, null));
		assertTrue(xlsx.text().contains("Revenue") && xlsx.text().contains("Q4") && xlsx.text().contains("4200"), xlsx.text());
		assertTrue(xlsx.text().contains("Notes") && xlsx.text().contains("audited"), xlsx.text());
		assertEquals(2L, RT.ensureLong(xlsx.meta().get(DocumentsAdapter.K_SHEETS)).longValue());

		Extraction pptx = adapter.extract(request(Fixtures.pptx("Slide one text", "Slide two text"),
			"deck.pptx", 0, null, null));
		assertEquals(2, pptx.pages());
		assertTrue(pptx.text().contains("--- slide 1 ---") && pptx.text().contains("Slide one text"), pptx.text());
		assertTrue(pptx.text().contains("--- slide 2 ---") && pptx.text().contains("Slide two text"), pptx.text());
		Extraction second = adapter.extract(request(Fixtures.pptx("Slide one text", "Slide two text"),
			"deck.pptx", 0, 2, 2));
		assertFalse(second.text().contains("Slide one"), second.text());
	}

	@Test
	public void testPlainTextPassesThroughAndUnknownTypesAreRefused() throws Exception {
		Extraction txt = adapter.extract(request("just notes".getBytes(StandardCharsets.UTF_8), "notes.md", 0, null, null));
		assertEquals("just notes", txt.text());
		assertNull(txt.pages());

		assertFalse(adapter.supports("application/zip", "archive.zip"));
		assertFalse(adapter.supports("image/heic", "photo.heic"));
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
			() -> adapter.extract(request(new byte[] {0x50, 0x4b}, "archive.zip", 0, null, null)));
		assertTrue(refused.getMessage().contains("supported: pdf, docx"), refused.getMessage());

		// A file that claims to be a PDF but is not fails with the reason, not a stack trace.
		IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
			() -> adapter.extract(request("not a pdf".getBytes(StandardCharsets.UTF_8), "x.pdf", 0, null, null)));
		assertTrue(broken.getMessage().startsWith("Cannot read x.pdf as pdf:"), broken.getMessage());
	}

	@Test
	public void testConfigureCap() {
		assertEquals(DocumentsAdapter.DEFAULT_MAX_CHARS, adapter.getMaxChars());
		assertTrue(adapter.configure(Maps.of("maxChars", 500), false));
		assertEquals(500, adapter.getMaxChars());
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("maxChars", 0), false));
		assertThrows(IllegalArgumentException.class, () -> adapter.configure(Maps.of("maxChars", "lots"), false));
		assertTrue(adapter.configure(Maps.empty(), false));
		assertEquals(DocumentsAdapter.DEFAULT_MAX_CHARS, adapter.getMaxChars());
		assertNotNull(RT.getIn(adapter.info(), "supported"));
	}

	@Test
	public void testParsePages() {
		assertNull(TextExtractor.parsePages(null));
		assertEquals(3, TextExtractor.parsePages(Strings.create("3"))[0]);
		Integer[] range = TextExtractor.parsePages(Strings.create("3-5"));
		assertEquals(3, range[0]);
		assertEquals(5, range[1]);
		Integer[] open = TextExtractor.parsePages(Strings.create("4-"));
		assertEquals(4, open[0]);
		assertNull(open[1]);
		Integer[] map = TextExtractor.parsePages(Maps.of("first", 2));
		assertEquals(2, map[0]);
		assertNull(map[1]);
		assertThrows(IllegalArgumentException.class, () -> TextExtractor.parsePages(Strings.create("three")));
	}

	// ========== Through the venue ==========

	@Test
	public void testFileReadExtractAndTheExtractOp() throws Exception {
		Path docs = Files.createDirectory(temp.resolve("docs"));
		Files.write(docs.resolve("report.pdf"), Fixtures.pdf("Quarterly Report", "Page one revenue", "Page two costs"));
		Files.writeString(docs.resolve("notes.txt"), "plain notes");
		Engine engine = Engine.createTemp(Maps.of(
			Config.USERS, Maps.of(Config.AUTO_CREATE, true),
			"file", Maps.of("roots", Maps.of("docs", docs.toAbsolutePath().toString()))));
		try {
			engine.registerAdapter(new DocumentsAdapter());
			Engine.addDemoAssets(engine);
			RequestContext ctx = RequestContext.of(Strings.create("did:test:documents"));

			// file:read mode=extract routes to the module, page range and all.
			Job read = engine.jobs().invokeOperation("v/ops/file/read", Maps.of(
				"root", "docs", "path", "report.pdf", "mode", "extract", "pages", "2"), ctx);
			ACell out = read.awaitResult(10_000);
			assertEquals(Status.COMPLETE, read.getStatus(), String.valueOf(read.getErrorMessage()));
			String text = RT.getIn(out, "text").toString();
			assertTrue(text.startsWith("--- page 2 ---") && text.contains("Page two costs"), text);
			assertFalse(text.contains("Page one"), text);
			assertEquals("application/pdf", RT.getIn(out, "mime").toString());
			assertEquals(2L, RT.ensureLong(RT.getIn(out, "pages")).longValue());
			assertEquals(Strings.create("Quarterly Report"), RT.getIn(out, "meta", "title"));

			// Plain text still extracts (as itself), and the other modes are untouched.
			Job notes = engine.jobs().invokeOperation("v/ops/file/read", Maps.of(
				"root", "docs", "path", "notes.txt", "mode", "extract"), ctx);
			assertEquals("plain notes", RT.getIn(notes.awaitResult(10_000), "text").toString());
			Job raw = engine.jobs().invokeOperation("v/ops/file/read", Maps.of(
				"root", "docs", "path", "notes.txt"), ctx);
			assertEquals("plain notes", RT.getIn(raw.awaitResult(10_000), "content").toString());

			// documents:extract for bytes in hand.
			String b64 = Base64.getEncoder().encodeToString(Fixtures.docx("Memo", "Hello from docx"));
			Job op = engine.jobs().invokeOperation("v/ops/documents/extract", Maps.of(
				"bytes", b64, "filename", "memo.docx"), ctx);
			ACell extracted = op.awaitResult(10_000);
			assertEquals(Status.COMPLETE, op.getStatus(), String.valueOf(op.getErrorMessage()));
			assertTrue(RT.getIn(extracted, "text").toString().contains("Hello from docx"));
			assertEquals(Strings.create("Memo"), RT.getIn(extracted, "meta", "title"));

			// The skill and the info record are published with the module.
			assertNotNull(engine.resolvePath(Strings.create("v/skills/data/documents"), engine.venueContext()));
			ACell info = engine.resolvePath(Strings.create("v/info/adapters/documents"), engine.venueContext());
			assertNotNull(info, "adapter info is published");
			assertNotNull(RT.getIn(info, "supported"), String.valueOf(info));
		} finally {
			engine.close();
		}
	}
}

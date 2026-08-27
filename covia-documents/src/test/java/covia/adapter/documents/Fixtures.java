package covia.adapter.documents;

import java.awt.Rectangle;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Documents built in-test, so nothing binary is checked in. */
final class Fixtures {

	private Fixtures() {}

	/** A PDF with one page per string; an empty string leaves that page without text. */
	static byte[] pdf(String title, String... pages) throws IOException {
		try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String text : pages) {
				PDPage page = new PDPage();
				doc.addPage(page);
				if (text == null || text.isEmpty()) continue;
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					cs.beginText();
					cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					cs.newLineAtOffset(60, 700);
					cs.showText(text);
					cs.endText();
				}
			}
			if (title != null) {
				doc.getDocumentInformation().setTitle(title);
				doc.getDocumentInformation().setAuthor("Covia Tests");
			}
			doc.save(out);
			return out.toByteArray();
		}
	}

	static byte[] docx(String title, String... paragraphs) throws IOException {
		try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String p : paragraphs) doc.createParagraph().createRun().setText(p);
			if (title != null) doc.getProperties().getCoreProperties().setTitle(title);
			doc.write(out);
			return out.toByteArray();
		}
	}

	static byte[] xlsx() throws IOException {
		try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XSSFSheet sheet = wb.createSheet("Revenue");
			sheet.createRow(0).createCell(0).setCellValue("Quarter");
			sheet.getRow(0).createCell(1).setCellValue("Amount");
			sheet.createRow(1).createCell(0).setCellValue("Q4");
			sheet.getRow(1).createCell(1).setCellValue(4200);
			wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("audited");
			wb.write(out);
			return out.toByteArray();
		}
	}

	static byte[] pptx(String... slides) throws IOException {
		try (XMLSlideShow show = new XMLSlideShow(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String text : slides) {
				XSLFSlide slide = show.createSlide();
				XSLFTextBox box = slide.createTextBox();
				box.setAnchor(new Rectangle(50, 50, 500, 100));
				box.setText(text);
			}
			show.write(out);
			return out.toByteArray();
		}
	}
}

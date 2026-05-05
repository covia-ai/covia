package covia.utils;

import java.util.Map;

/**
 * MIME type detection utilities shared by DLFS/vault operations.
 *
 * <p>Two strategies, in order of preference:
 * <ol>
 *   <li>Filename extension lookup — cheap, correct for common cases.</li>
 *   <li>Magic-byte sniff of the first few bytes — fallback for
 *       extensionless files or when the extension is unreliable.</li>
 * </ol>
 *
 * <p>If neither yields a match, returns {@code application/octet-stream}.
 */
public final class MimeUtils {

	private MimeUtils() {}

	private static final String OCTET = "application/octet-stream";

	private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
		// Images
		Map.entry("png", "image/png"),
		Map.entry("jpg", "image/jpeg"),
		Map.entry("jpeg", "image/jpeg"),
		Map.entry("gif", "image/gif"),
		Map.entry("webp", "image/webp"),
		Map.entry("bmp", "image/bmp"),
		Map.entry("svg", "image/svg+xml"),
		Map.entry("ico", "image/x-icon"),
		Map.entry("tif", "image/tiff"),
		Map.entry("tiff", "image/tiff"),
		Map.entry("heic", "image/heic"),
		Map.entry("heif", "image/heif"),
		Map.entry("avif", "image/avif"),
		// Text
		Map.entry("txt", "text/plain"),
		Map.entry("md", "text/markdown"),
		Map.entry("csv", "text/csv"),
		Map.entry("log", "text/plain"),
		Map.entry("html", "text/html"),
		Map.entry("htm", "text/html"),
		Map.entry("css", "text/css"),
		Map.entry("xml", "text/xml"),
		// Structured
		Map.entry("json", "application/json"),
		Map.entry("yaml", "application/yaml"),
		Map.entry("yml", "application/yaml"),
		// Documents
		Map.entry("pdf", "application/pdf"),
		// Code
		Map.entry("js", "application/javascript"),
		Map.entry("mjs", "application/javascript")
	);

	/**
	 * Guesses the MIME type of a file from its name. Does not look at the content.
	 */
	public static String guessByName(String name) {
		if (name == null) return OCTET;
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) return OCTET;
		String ext = name.substring(dot + 1).toLowerCase();
		return BY_EXTENSION.getOrDefault(ext, OCTET);
	}

	/**
	 * Sniffs the first bytes of a file to identify common binary formats.
	 * Returns null if the content does not match any known signature.
	 */
	public static String sniff(byte[] bytes) {
		if (bytes == null || bytes.length < 4) return null;
		int n = bytes.length;

		// PNG: 89 50 4E 47 0D 0A 1A 0A
		if (n >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
				&& bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
			return "image/png";
		}
		// JPEG: FF D8 FF
		if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		// GIF: "GIF87a" or "GIF89a"
		if (n >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8'
				&& (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a') {
			return "image/gif";
		}
		// WebP: "RIFF"...."WEBP"
		if (n >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
			return "image/webp";
		}
		// BMP: "BM"
		if (bytes[0] == 'B' && bytes[1] == 'M') return "image/bmp";
		// TIFF: "II*\0" or "MM\0*"
		if (bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == '*' && bytes[3] == 0) return "image/tiff";
		if (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0 && bytes[3] == '*') return "image/tiff";
		// HEIC/AVIF: "ftyp" at offset 4, brand at offset 8
		if (n >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
			String brand = new String(bytes, 8, 4);
			if ("heic".equals(brand) || "heix".equals(brand) || "mif1".equals(brand)) return "image/heic";
			if ("avif".equals(brand)) return "image/avif";
		}
		// PDF: "%PDF-"
		if (n >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
			return "application/pdf";
		}
		// SVG (text but identifiable)
		if (n >= 4 && bytes[0] == '<' && bytes[1] == 's' && bytes[2] == 'v' && bytes[3] == 'g') return "image/svg+xml";

		return null;
	}

	/**
	 * Best-effort MIME guess combining name and content. If the name gives a
	 * confident answer, uses that; otherwise falls back to sniffing.
	 */
	public static String guess(String name, byte[] bytes) {
		String byName = guessByName(name);
		if (!OCTET.equals(byName)) return byName;
		String sniffed = sniff(bytes);
		return sniffed != null ? sniffed : OCTET;
	}
}
